package local.historymcp;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

final class HistoryService implements AutoCloseable {
    private final MontoyaApi api;
    private final LinkedHashMap<String,State> sessions=new LinkedHashMap<>();
    private final int maxSessions=prop("historymcp.maxSessions",3);
    private final long ttlMs=prop("historymcp.sessionTtlMs",600000);
    private final SearchSession.Limits limits=new SearchSession.Limits(prop("historymcp.maxUnique",100000),
        prop("historymcp.maxRetainedChars",16000000),prop("historymcp.maxSnippetChars",200000),prop("historymcp.maxActiveTextChars",128000000));
    private boolean closed;

    private static final class State {
        SearchSession search;
        String next,previous;
        Map<String,Object> last;
        long expires;
        State(SearchSession search){
            this.search=search;
        }
    }

    HistoryService(MontoyaApi api){
        this.api=api;
    }

    static int prop(String key,int fallback){
        int n=Integer.parseInt(System.getProperty(key,""+fallback));
        if(n<=0)
            throw new IllegalArgumentException(key+" must be positive");
        return n;
    }

    private final class Entry implements SearchSession.Entry {
        final ProxyHttpRequestResponse value;
        Entry(ProxyHttpRequestResponse value){
            this.value=value;
        }

        public String url(){
            return value.request().url();
        }

        public boolean inScope(){
            return api.scope().isInScope(url());
        }

        public Map<String,Object> metadata(){
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("history_id",value.id());
            m.put("request_timestamp",value.time().toString());
            m.put("url",url());
            m.put("method",value.request().method());
            if(value.hasResponse())
                m.put("status",value.response().statusCode());
            return m;
        }

        public String text(String target){
            return switch(target){
                case "request_body"->value.request().bodyToString();
                case "request"->value.request().toString();
                case "response_body"->value.hasResponse()?value.response().bodyToString():null;
                case "response"->value.hasResponse()?value.response().toString():null;
                default->throw new IllegalArgumentException("Invalid target");
            };
        }
    }

    static String text(JsonNode a,String key,String fallback){
        JsonNode n=a.get(key);
        if(n==null)
            return fallback;
        if(!n.isTextual())
            throw new IllegalArgumentException(key+" must be a string");
        return n.textValue();
    }

    static int number(JsonNode a,String key,int fallback,int min,int max){
        JsonNode n=a.get(key);
        if(n==null)
            return fallback;
        if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<min||n.intValue()>max)
            throw new IllegalArgumentException(key+" out of range");
        return n.intValue();
    }

    private static void fields(JsonNode a,Set<String> allowed){
        a.fieldNames().forEachRemaining(k->{
            if(!allowed.contains(k))
                throw new IllegalArgumentException("Unexpected argument: "+k);
        });
    }

    synchronized void expire(){
        sessions.values().removeIf(s->s.expires<System.currentTimeMillis());
    }

    @Override public synchronized void close(){
        closed=true;sessions.clear();
    }

    synchronized Object call(JsonNode a,BooleanSupplier cancel){
        if(closed)
            throw new IllegalStateException("Extension unloaded");
        if(!a.isObject())
            throw new IllegalArgumentException("arguments must be an object");
        expire();
        if(a.has("cursor")){
            if(a.size()!=1)
                throw new IllegalArgumentException("Continuation accepts cursor only");

            String cursor=text(a,"cursor",null);
            for(var e:sessions.entrySet()){
                State s=e.getValue();
                if(cursor.equals(s.previous)){
                    s.expires=System.currentTimeMillis()+ttlMs;
                    return s.last;
                }
                if(cursor.equals(s.next))
                    return advance(e.getKey(),s,cursor,cancel);
            }
            throw new IllegalArgumentException("Invalid, old, or expired cursor; start a new search");
        }

        String operation=text(a,"operation","search");
        if(operation.equals("read"))
            return read(a,cancel);
        if(!operation.equals("search"))
            throw new IllegalArgumentException("operation must be search or read");

        fields(a,Set.of("operation","pattern","target","context_before","context_after","url_regex","in_scope_only","max_matches","work_budget_ms"));

        sessions.entrySet().removeIf(e->e.getValue().next==null);
        if(sessions.size()>=maxSessions)
            throw new IllegalStateException("Session capacity reached; wait for idle-session expiry or unload/reload. Existing searches were not evicted");

        String regex=text(a,"pattern",null);
        if(regex==null||regex.isEmpty()||regex.length()>8192)
            throw new IllegalArgumentException("pattern required: 1..8192 characters");

        String target=text(a,"target","response_body");
        if(!Set.of("request","response","request_body","response_body","both").contains(target))
            throw new IllegalArgumentException("Invalid target");

        String ur=text(a,"url_regex",null);
        if(ur!=null&&ur.length()>8192)
            throw new IllegalArgumentException("url_regex too long");

        boolean scope=false;
        if(a.has("in_scope_only")){
            if(!a.get("in_scope_only").isBoolean())
                throw new IllegalArgumentException("in_scope_only must be boolean");
            scope=a.get("in_scope_only").booleanValue();
        }

        var q=new SearchSession.Query(Pattern.compile(regex),ur==null?null:Pattern.compile(ur),target,
            number(a,"context_before",200,0,10000),number(a,"context_after",200,0,10000),scope,
            number(a,"max_matches",50,1,500),number(a,"work_budget_ms",5000,10,20000));

        long start=System.nanoTime();
        List<Entry> entries=new ArrayList<>();
        for(var item:api.proxy().history()){
            if(cancel.getAsBoolean())
                throw new IllegalStateException("Cancelled during history snapshot");
            entries.add(new Entry(item));
        }
        State s=new State(new SearchSession(entries,q,limits,(System.nanoTime()-start)/1_000_000));
        String key=UUID.randomUUID().toString();
        sessions.put(key,s);
        return advance(key,s,null,cancel);
    }

    private Object advance(String key,State s,String cursor,BooleanSupplier cancel){
        try{
            Map<String,Object> result=s.search.next(cancel);
            s.previous=cursor;
            s.next=(Boolean.TRUE.equals(result.get("search_complete")))?null:UUID.randomUUID().toString();
            result.put("next_cursor",s.next);
            s.last=result;
            s.expires=System.currentTimeMillis()+ttlMs;
            // Completed sessions retain the immediately preceding response for retry only.
            if(s.next==null){
                s.search=null;
                if(cursor==null)
                    sessions.remove(key);
            }
            return result;
        }catch(RuntimeException | LinkageError e){
            sessions.remove(key);
            throw e;
        }
    }

    private Object read(JsonNode a,BooleanSupplier cancel){
        fields(a,Set.of("operation","history_id","target","offset","length"));

        if(!a.has("history_id")||!a.has("offset"))
            throw new IllegalArgumentException("read requires history_id and offset");

        int id=number(a,"history_id",0,0,Integer.MAX_VALUE),offset=number(a,"offset",0,0,Integer.MAX_VALUE),length=number(a,"length",12000,1,100000);

        String target=text(a,"target","response_body");
        if(!Set.of("request","response","request_body","response_body").contains(target))
            throw new IllegalArgumentException("read target must identify one message representation; both is forbidden");

        ProxyHttpRequestResponse found=null;
        for(var value:api.proxy().history()){
            if(cancel.getAsBoolean())
                throw new IllegalStateException("Read cancelled");
            if(value.id()==id){
                found=value;
                break;
            }
        }

        if(found==null)
            throw new IllegalArgumentException("History ID not found; entry may have been removed or project changed");

        String text=new Entry(found).text(target);
        if(text==null)
            throw new IllegalArgumentException("Entry has no response");

        if(cancel.getAsBoolean())
            throw new IllegalStateException("Read cancelled");

        if(offset>text.length())
            throw new IllegalArgumentException("offset exceeds selected text length: "+text.length());

        int end=(int)Math.min((long)text.length(),(long)offset+length);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("operation","read");
        out.put("history_id",id);
        out.put("target",target);
        out.put("start",offset);
        out.put("end",end);
        out.put("total_length",text.length());
        out.put("text",text.substring(offset,end));
        out.put("offset_units","UTF-16 code units");
        out.put("has_more_before",offset>0);
        out.put("has_more_after",end<text.length());
        out.put("url",found.request().url());
        out.put("method",found.request().method());
        return out;
    }
}
