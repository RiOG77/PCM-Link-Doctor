package llc.riog.pcmlinkbt;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.Locale;

public final class CaptureService extends Service {
    static final String ACTION_START="llc.riog.pcmlinkbt.START";
    static final String ACTION_STOP="llc.riog.pcmlinkbt.STOP";
    static final String ACTION_MARK="llc.riog.pcmlinkbt.MARK";
    static final String EXTRA_MAC="mac", EXTRA_NAME="name";
    private BluetoothAdapter adapter;
    private BluetoothHeadset headset;
    private BluetoothA2dp music;
    private String mac="", lastHfp="",lastA2dp="",lastBond="";
    private boolean running=false,registered=false;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final BluetoothProfile.ServiceListener listener=new BluetoothProfile.ServiceListener(){
        @Override public void onServiceConnected(int profile,BluetoothProfile p){
            if(profile==BluetoothProfile.HEADSET&&p instanceof BluetoothHeadset)headset=(BluetoothHeadset)p;
            if(profile==BluetoothProfile.A2DP&&p instanceof BluetoothA2dp)music=(BluetoothA2dp)p;
            sample();
        }
        @Override public void onServiceDisconnected(int profile){
            if(profile==BluetoothProfile.HEADSET)headset=null;
            if(profile==BluetoothProfile.A2DP)music=null;
            if(running)LogStore.append(CaptureService.this,"PROFILE_PROXY","unavailable "+profile);
        }
    };
    private final Runnable tick=new Runnable(){
        @Override public void run(){
            if(running){
                LogStore.prefs(CaptureService.this).edit().putLong(LogStore.HEARTBEAT,System.currentTimeMillis()).apply();
                sample();handler.postDelayed(this,900);
            }
        }
    };
    private BluetoothDevice getDevice(Intent i){
        if(Build.VERSION.SDK_INT>=33)return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class);
        return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String action=i.getAction();if(action==null||!running)return;
            if(action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)){
                LogStore.append(c,"ADAPTER","state="+i.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1));
                return;
            }
            BluetoothDevice d=getDevice(i);
            if(d==null||!mac.equalsIgnoreCase(d.getAddress()))return;
            String type="BT_EVENT",detail=action;
            if(action.equals(BluetoothDevice.ACTION_ACL_CONNECTED))type="ACL";
            else if(action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED))type="ACL";
            else if(action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)){
                type="BOND";detail="state="+bond(i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,-1));
            }else if(action.equals(BluetoothDevice.ACTION_PAIRING_REQUEST)){
                type="PAIRING_REQUEST";detail="Android delivered pairing request; no PIN captured";
            }else if(action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)){
                type="HFP";detail="state="+state(i.getIntExtra(BluetoothProfile.EXTRA_STATE,-1));
            }else if(action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)){
                type="HFP_AUDIO";detail="state="+i.getIntExtra(BluetoothProfile.EXTRA_STATE,-1)+" (SCO may be idle when not in a call)";
            }else if(action.equals(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)){
                type="A2DP";detail="state="+state(i.getIntExtra(BluetoothProfile.EXTRA_STATE,-1));
            }else if(action.equals(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)){
                type="A2DP_MEDIA";detail="state="+i.getIntExtra(BluetoothProfile.EXTRA_STATE,-1);
            }else if(action.equals(BluetoothDevice.ACTION_UUID)){
                type="SDP";android.os.ParcelUuid[] uu=d.getUuids();
                detail=uu==null?"No cached UUIDs":services(uu);
            }
            LogStore.append(c,type,detail);
            sample();
        }
    };
    static String state(int v){
        if(v==BluetoothProfile.STATE_CONNECTED)return "CONNECTED";
        if(v==BluetoothProfile.STATE_CONNECTING)return "CONNECTING";
        if(v==BluetoothProfile.STATE_DISCONNECTED)return "DISCONNECTED";
        if(v==BluetoothProfile.STATE_DISCONNECTING)return "DISCONNECTING";
        return "UNKNOWN";
    }
    static String bond(int v){
        if(v==BluetoothDevice.BOND_BONDED)return "BONDED";
        if(v==BluetoothDevice.BOND_BONDING)return "BONDING";
        if(v==BluetoothDevice.BOND_NONE)return "NONE";
        return "UNKNOWN";
    }
    static String services(android.os.ParcelUuid[] uu){
        if(uu==null||uu.length==0)return "No UUIDs";
        StringBuilder b=new StringBuilder();
        for(android.os.ParcelUuid p:uu){
            String s=p.toString().toLowerCase(Locale.ROOT);
            String kind=s.startsWith("0000111f-")?"HFP_AG":
                s.startsWith("0000111e-")?"HFP_HF":
                s.startsWith("0000110b-")?"A2DP_SINK":
                s.startsWith("0000110a-")?"A2DP_SOURCE":"UUID";
            if(b.length()>0)b.append(" | ");
            b.append(kind).append(":").append(s.substring(0,8));
        }
        return b.toString();
    }
    @Override public void onCreate(){
        super.onCreate();BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=m==null?null:m.getAdapter();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        String a=intent==null?"":intent.getAction();
        if(ACTION_STOP.equals(a)){shutdown();return START_NOT_STICKY;}
        if(ACTION_MARK.equals(a)){
            if(running)LogStore.append(this,"MARK","PCM spinner observed by user");
            return START_NOT_STICKY;
        }
        if(!ACTION_START.equals(a)||intent==null)return START_NOT_STICKY;
        if(running)return START_NOT_STICKY;
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY;}
        mac=intent.getStringExtra(EXTRA_MAC);String name=intent.getStringExtra(EXTRA_NAME);
        if(mac==null||mac.isEmpty()){stopSelf();return START_NOT_STICKY;}
        if(name==null)name="Porsche";
        LogStore.start(this,mac,name);running=true;notifyForeground();
        IntentFilter f=new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.addAction(BluetoothDevice.ACTION_UUID);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver,f);
        registered=true;
        if(adapter!=null) {
            adapter.getProfileProxy(this,listener,BluetoothProfile.HEADSET);
            adapter.getProfileProxy(this,listener,BluetoothProfile.A2DP);
        }
        handler.post(tick);return START_NOT_STICKY;
    }
    private void notifyForeground(){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("pcm_bt","PCM Linker · диагностика",NotificationManager.IMPORTANCE_LOW));
        Intent launch=new Intent(this,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,"pcm_bt")
            .setContentTitle("PCM Linker · запись Bluetooth")
            .setContentText("События Porsche фиксируются локально")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pending).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(12,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(12,n);
    }
    private void sample(){
        if(!running||adapter==null)return;
        try{
            BluetoothDevice d=adapter.getRemoteDevice(mac);
            String b=bond(d.getBondState());
            if(!b.equals(lastBond)){lastBond=b;LogStore.append(this,"BOND","state="+b);}
            if(headset!=null){
                String state=state(headset.getConnectionState(d));
                if(!state.equals(lastHfp)){lastHfp=state;LogStore.append(this,"HFP","state="+state);}
            }
            if(music!=null){
                String state=state(music.getConnectionState(d));
                if(!state.equals(lastA2dp)){lastA2dp=state;LogStore.append(this,"A2DP","state="+state);}
            }
        }catch(Exception e){LogStore.append(this,"READ_ERROR",e.getClass().getSimpleName());}
    }
    private void shutdown(){
        if(running){sample();LogStore.append(this,"SESSION","END");}
        running=false;handler.removeCallbacks(tick);
        if(registered){try{unregisterReceiver(receiver);}catch(Exception ignored){} registered=false;}
        if(adapter!=null){
            try{if(headset!=null)adapter.closeProfileProxy(BluetoothProfile.HEADSET,headset);}catch(Exception ignored){}
            try{if(music!=null)adapter.closeProfileProxy(BluetoothProfile.A2DP,music);}catch(Exception ignored){}
        }
        headset=null;music=null;
        LogStore.prefs(this).edit().putBoolean(LogStore.RUNNING,false).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy(){shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
