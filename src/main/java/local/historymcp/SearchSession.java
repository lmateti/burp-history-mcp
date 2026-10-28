package local.historymcp;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Stateful matcher is retained across calls: never restart regex on substrings. */
public final class SearchSession {
    public interface Entry {
        Map<String,Object> metadata();
        String url();
        boolean inScope();
        String text(String target); // null if response missing
    }
    public record Query(Pattern pattern,Pattern urlPattern,String target,int before,int after,
                        boolean scope,int pageSize,int workMs){}
    public record Limits(int unique,long chars,int snippet,int activeText){}
    static final class Count {long n=1;}
    private final List<? extends Entry> entries;
    private final Query query;
    private final Limits limits;
    private final LinkedHashMap<String,Count> seen=new LinkedHashMap<>();
    private int index,side;
    private Entry entry;
    private Map<String,Object> metadata;
    private String activeTarget,activeText;
    private Matcher matcher;
    private long examined,selected,messages,characters,raw,retainedChars,workNanos;
    private boolean complete;
    private final long historyMs;

    public SearchSession(List<? extends Entry> entries,Query query,Limits limits,long historyMs){
        this.entries=entries;
        this.query=query;
        this.limits=limits;
        this.historyMs=historyMs;
        index=entries.size()-1;
    }

    private static void cancelled(BooleanSupplier cancel){
        if(cancel.getAsBoolean())
            throw new IllegalStateException("Request cancelled; search session discarded");
    }

    private boolean prepare(BooleanSupplier cancel,long deadline){
        while(matcher==null){
            cancelled(cancel);
            if(System.nanoTime()>=deadline)
                return false;

            if(entry==null){
                if(index<0){complete=true;
                    return false;
                }

                entry=entries.get(index--);
                examined++;
                side=0;
                if(query.urlPattern()!=null && !query.urlPattern().matcher(entry.url()).find()){
                    entry=null;
                    continue;
                }
                if(query.scope() && !entry.inScope()){
                    entry=null;
                    continue;
                }

                selected++;
                metadata=entry.metadata();
                if(System.nanoTime()>=deadline)
                    return false;
            }

            String target;
            if(query.target().equals("both")){
                if(side>=2){
                    entry=null;
                    continue;
                }
                target=side++==0?"request":"response";
            }else{
                if(side++>0){
                    entry=null;
                    continue;
                }
                target=query.target();
            }
            String text=entry.text(target);
            cancelled(cancel);
            if(text==null)
                continue;
            if(text.length()>limits.activeText())
                throw new IllegalStateException("Message exceeds active-text limit; search discarded");

            activeText=text;
            activeTarget=target;
            messages++;
            characters+=text.length();
            matcher=query.pattern().matcher(text);
        }
        return true;
    }

    public Map<String,Object> next(BooleanSupplier cancel){
        if(complete)
            throw new IllegalStateException("Search already complete");
        long started=System.nanoTime(),deadline=started+query.workMs()*1_000_000L;
        List<Map<String,Object>> results=new ArrayList<>();
        List<Count> pageCounts=new ArrayList<>();
        int outputChars=0;
        String reason="work_budget";
        while(true){
            cancelled(cancel);

            if(results.size()>=query.pageSize() || outputChars>=250000){
                reason="page_full";
                break;
            }

            if(System.nanoTime()>=deadline)
                break;

            if(!prepare(cancel,deadline)){
                if(complete)
                    reason="exhausted";
                break;
            }

            if(System.nanoTime()>=deadline)
                break;

            // A find() is indivisible. It can overrun the cooperative budget.
            boolean found=matcher.find();
            cancelled(cancel);
            if(!found){
                matcher=null;
                activeText=null;
                continue;
            }

            int start=matcher.start(),end=matcher.end();
            int a=Math.max(0,start-query.before());
            int b=(int)Math.min((long)activeText.length(),(long)end+query.after());

            if(b-a>limits.snippet())
                throw new IllegalStateException("Snippet exceeds limit; reduce context/narrow regex. Search discarded");

            String snippet=activeText.substring(a,b);
            raw++;
            Count count=seen.get(snippet);
            if(count!=null){
                count.n++;
                continue;
            }

            if(seen.size()>=limits.unique() || retainedChars+snippet.length()>limits.chars())
                throw new IllegalStateException("Unique-snippet storage limit reached; narrow query. Search discarded");

            count=new Count();
            seen.put(snippet,count);
            retainedChars+=snippet.length();
            Map<String,Object> rep=new LinkedHashMap<>(metadata);
            rep.put("target",activeTarget);
            rep.put("match_start",start);
            rep.put("match_end",end);
            rep.put("snippet_start",a);
            rep.put("match_start_in_snippet",start-a);
            rep.put("match_end_in_snippet",end-a);
            Map<String,Object> hit=new LinkedHashMap<>();
            hit.put("snippet",snippet);
            hit.put("representative",rep);
            results.add(hit);
            pageCounts.add(count);
            outputChars+=snippet.length();
        }

        for(int i=0;i<results.size();i++)
            results.get(i).put("occurrence_count_so_far",pageCounts.get(i).n);

        long elapsed=System.nanoTime()-started;
        workNanos+=elapsed;
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("operation","search");
        out.put("results",results);
        out.put("returned_results",results.size());
        out.put("search_complete",complete);
        out.put("stop_reason",reason);
        out.put("history_snapshot_entries",entries.size());
        out.put("history_entries_examined",examined);
        out.put("selected_entries",selected);
        out.put("messages_searched",messages);
        out.put("characters_searched",characters);
        out.put("raw_match_count_so_far",raw);
        out.put("unique_snippet_count_so_far",seen.size());
        out.put("duplicates_removed_so_far",raw-seen.size());
        out.put("history_access_ms",historyMs);
        out.put("call_work_ms",elapsed/1_000_000);
        out.put("total_work_ms",workNanos/1_000_000+historyMs);
        out.put("offset_units","UTF-16 code units");
        out.put("counts_final",complete);
        out.put("count_note","Per-result counts are observed at page emission; earlier pages are not updated. Aggregate counts are final only when search_complete=true.");
        return out;
    }
}
