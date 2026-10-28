package local.historymcp;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.*;
import java.util.Map;

public final class ToolDocs {
    public static final String NAME="search_proxy_history";
    private static final Map<String,Object> DEFINITION=load();
    private static Map<String,Object> load(){
        try(InputStream stream=ToolDocs.class.getResourceAsStream("/tool.json")){
            if(stream==null)
                throw new IllegalStateException("Missing tool.json resource");
            return new ObjectMapper().readValue(stream,new TypeReference<Map<String,Object>>(){});
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }
    public static Map<String,Object> definition(){
        return DEFINITION;
    }
}
