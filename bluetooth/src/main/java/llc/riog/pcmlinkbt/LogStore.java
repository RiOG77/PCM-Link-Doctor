package llc.riog.pcmlinkbt;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

final class LogStore {
    static final String PREFS="bt_log", FILE="file", MAC="mac", NAME="name", RUNNING="running", HEARTBEAT="heartbeat";
    static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    static String now(){
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());
    }
    static synchronized void start(Context c,String mac,String name){
        String filename="capture_"+System.currentTimeMillis()+".jsonl";
        prefs(c).edit().putString(FILE,filename).putString(MAC,mac)
            .putString(NAME,name).putBoolean(RUNNING,true).putLong(HEARTBEAT,System.currentTimeMillis()).commit();
        append(c,"SESSION","BEGIN");
    }
    static boolean isRecording(Context c){
        SharedPreferences p=prefs(c);
        return p.getBoolean(RUNNING,false) && System.currentTimeMillis()-p.getLong(HEARTBEAT,0)<12000;
    }
    static synchronized void append(Context c,String type,String detail){
        String filename=prefs(c).getString(FILE,"");
        if(filename.isEmpty())return;
        try{
            JSONObject j=new JSONObject().put("at",now()).put("type",type).put("detail",detail);
            File dir=new File(c.getFilesDir(),"captures");if(!dir.exists())dir.mkdirs();
            try(FileWriter w=new FileWriter(new File(dir,new File(filename).getName()),true)){w.write(j.toString()+"\n");}
        }catch(Exception ignored){}
    }
    static JSONArray events(Context c){
        JSONArray a=new JSONArray();String filename=prefs(c).getString(FILE,"");
        if(filename.isEmpty())return a;
        File f=new File(new File(c.getFilesDir(),"captures"),new File(filename).getName());
        if(!f.isFile())return a;
        try(BufferedReader b=new BufferedReader(new FileReader(f))){
            String line;while((line=b.readLine())!=null&&a.length()<6000){
                try{a.put(new JSONObject(line));}catch(Exception ignored){}
            }
        }catch(Exception ignored){}
        return a;
    }
    static JSONObject export(Context c) throws Exception {
        JSONArray a=events(c);
        JSONObject status=new JSONObject();
        boolean seenHfp=false,seenA2dp=false,marked=false;
        String hfpLast="not observed",a2dpLast="not observed";
        for(int i=0;i<a.length();i++){
            JSONObject e=a.optJSONObject(i);if(e==null)continue;
            String type=e.optString("type"),detail=e.optString("detail");
            if(type.equals("HFP")){
                hfpLast=detail;if(detail.contains("CONNECTED"))seenHfp=true;
            }
            if(type.equals("A2DP")){
                a2dpLast=detail;if(detail.contains("CONNECTED"))seenA2dp=true;
            }
            if(type.equals("MARK"))marked=true;
        }
        status.put("hfp_connected_seen",seenHfp).put("a2dp_connected_seen",seenA2dp)
            .put("hfp_last",hfpLast).put("a2dp_last",a2dpLast).put("pcm_spinner_marked",marked)
            .put("scope","Android public HFP/A2DP states only; PCM6 internal state unknown");
        return new JSONObject()
            .put("schema","pcm-linker-bt-v1")
            .put("generated_at",now())
            .put("phone",new JSONObject().put("maker",Build.MANUFACTURER).put("model",Build.MODEL)
                .put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT)
                .put("security_patch",Build.VERSION.SECURITY_PATCH))
            .put("target_name",prefs(c).getString(NAME,""))
            .put("privacy","No Bluetooth MAC address, contacts, call content, or audio exported")
            .put("summary",status).put("events",a);
    }
}
