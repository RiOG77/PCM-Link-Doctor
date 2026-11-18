package llc.riog.pcmlinkbt;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int BG=0xFF0C1422,CARD=0xFF1B283C,STROKE=0xFF304156,
        WHITE=0xFFF4F7FC,MUTED=0xFF9FACC0,BLUE=0xFF50B5FF,GREEN=0xFF64DFAD,
        AMBER=0xFFFFC36A;
    private static final int REQ_PERMS=11,REQ_EXPORT=12;
    private BluetoothAdapter adapter;
    private BluetoothHeadset hfp;
    private BluetoothA2dp a2dp;
    private Spinner spinner;
    private TextView status, services, timeline, caption;
    private Button record, scan;
    private final ArrayList<BluetoothDevice> devices=new ArrayList<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean foreground=false,proxies=false;
    private final Runnable tick=new Runnable(){
        @Override public void run(){if(foreground){refresh();main.postDelayed(this,1000);}}
    };
    private final BluetoothProfile.ServiceListener profileListener=new BluetoothProfile.ServiceListener(){
        @Override public void onServiceConnected(int p,BluetoothProfile proxy){
            if(p==BluetoothProfile.HEADSET&&proxy instanceof BluetoothHeadset)hfp=(BluetoothHeadset)proxy;
            if(p==BluetoothProfile.A2DP&&proxy instanceof BluetoothA2dp)a2dp=(BluetoothA2dp)proxy;
            refresh();
        }
        @Override public void onServiceDisconnected(int p){
            if(p==BluetoothProfile.HEADSET)hfp=null;
            if(p==BluetoothProfile.A2DP)a2dp=null;
            refresh();
        }
    };
    private BluetoothDevice targetFrom(Intent i){
        if(Build.VERSION.SDK_INT>=33)return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class);
        return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }
    private final BroadcastReceiver observer=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String action=i.getAction();if(action==null||!permitted())return;
            if(action.equals(BluetoothDevice.ACTION_FOUND)){
                BluetoothDevice d=targetFrom(i);
                if(d!=null && !contains(d.getAddress())){
                    devices.add(d);updateSelector(d.getAddress(),false);
                }
            }else if(action.equals(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)){
                scan.setText("Сканировать устройства");
            }else if(action.equals(BluetoothDevice.ACTION_UUID)){
                BluetoothDevice d=targetFrom(i);
                if(d!=null&&selected()!=null&&d.getAddress().equals(selected().getAddress())){
                    updateServices();
                    Toast.makeText(MainActivity.this,"Ответ SDP получен",Toast.LENGTH_SHORT).show();
                }
            }else if(action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)){
                updateSelector(currentMac(),true);
            }
            refresh();
        }
    };
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        BluetoothManager mgr=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=mgr==null?null:mgr.getAdapter();
        render();
        requestPermissionsIfNeeded();
    }
    @Override public void onResume(){
        super.onResume();foreground=true;
        if(permitted()){loadBonded();initProfiles();}
        registerObservers();main.removeCallbacks(tick);main.post(tick);
    }
    @Override public void onPause(){
        foreground=false;main.removeCallbacks(tick);unregisterObservers();super.onPause();
    }
    @Override public void onDestroy(){
        if(adapter!=null){
            try{if(hfp!=null)adapter.closeProfileProxy(BluetoothProfile.HEADSET,hfp);}catch(Exception ignored){}
            try{if(a2dp!=null)adapter.closeProfileProxy(BluetoothProfile.A2DP,a2dp);}catch(Exception ignored){}
        }
        super.onDestroy();
    }
    private boolean permitted(){
        return Build.VERSION.SDK_INT<31||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
    }
    private boolean canScan(){
        return Build.VERSION.SDK_INT<31||
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestPermissionsIfNeeded(){
        ArrayList<String> need=new ArrayList<>();
        if(!permitted())need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if(!canScan())need.add(Manifest.permission.BLUETOOTH_SCAN);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!need.isEmpty())requestPermissions(need.toArray(new String[0]),REQ_PERMS);
        else {loadBonded();initProfiles();}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==REQ_PERMS){
            if(permitted()){loadBonded();initProfiles();}
            else new AlertDialog.Builder(this).setMessage("Разреши «Устройства поблизости», чтобы видеть Bluetooth-профили.")
                .setPositiveButton("Настройки",(d,w)->openAppSettings()).setNegativeButton("Закрыть",null).show();
        }
    }
    private void openAppSettings(){
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:"+getPackageName())));
    }
    private void registerObservers(){
        IntentFilter f=new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_UUID);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(observer,f,Context.RECEIVER_EXPORTED);
        else registerReceiver(observer,f);
    }
    private void unregisterObservers(){try{unregisterReceiver(observer);}catch(Exception ignored){}}
    private void initProfiles(){
        if(proxies||!permitted()||adapter==null||!adapter.isEnabled())return;
        proxies=true;
        try{
            adapter.getProfileProxy(this,profileListener,BluetoothProfile.HEADSET);
            adapter.getProfileProxy(this,profileListener,BluetoothProfile.A2DP);
        }catch(Exception e){proxies=false;}
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius,boolean border){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));
        if(border)g.setStroke(dp(1),STROKE);return g;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean strong){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(strong)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout.LayoutParams lp(int width,int height){
        return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));
    }
    private void gap(LinearLayout l,int n){View v=new View(this);l.addView(v,lp(1,n));}
    private LinearLayout card(LinearLayout root){
        LinearLayout c=column();c.setPadding(dp(18),dp(17),dp(18),dp(17));
        c.setBackground(background(CARD,20,true));root.addView(c,lp(-1,-2));return c;
    }
    private Button button(String label,boolean primary){
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(label);b.setTextSize(15);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(primary?BG:WHITE);b.setMinHeight(dp(48));
        b.setBackground(background(primary?BLUE:0xFF30445F,12,false));
        return b;
    }
    private void render(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);scroll.setFillViewport(true);
        LinearLayout root=column();root.setPadding(dp(18),dp(27),dp(18),dp(30));
        scroll.addView(root);setContentView(scroll);
        root.addView(text("PCM Linker",29,WHITE,true));
        root.addView(text("Bluetooth · Porsche Cayenne / PCM6 · без root",13,MUTED,false));gap(root,20);
        LinearLayout selectCard=card(root);
        selectCard.addView(text("УСТРОЙСТВО",12,MUTED,true));gap(selectCard,8);
        spinner=new Spinner(this);
        selectCard.addView(spinner,lp(-1,50));gap(selectCard,7);
        selectCard.addView(text("Сначала выбери Cayenne_3093. Рабочий S24+ можно проверить отдельно.",12,MUTED,false));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                refresh();updateServices();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });gap(root,12);
        LinearLayout states=card(root);
        states.addView(text("Подключение",18,WHITE,true));gap(states,10);
        status=text("Ожидаем разрешение Bluetooth…",15,WHITE,false);
        status.setLineSpacing(dp(6),1.05f);states.addView(status);gap(root,12);
        LinearLayout control=card(root);
        control.addView(text("Управление и проверка",18,WHITE,true));gap(control,11);
        Button sdp=button("Запросить Bluetooth-сервисы (SDP)",true);
        control.addView(sdp,lp(-1,52));gap(control,7);
        sdp.setOnClickListener(v->requestSdp());
        services=text("Доступные сервисы ещё не проверены.",12,MUTED,false);
        services.setLineSpacing(dp(3),1.05f);control.addView(services);gap(control,13);
        Button pair=button("Запустить сопряжение",false);
        control.addView(pair,lp(-1,50));
        pair.setOnClickListener(v->pair());
        gap(control,7);
        scan=button("Сканировать устройства",false);control.addView(scan,lp(-1,50));
        scan.setOnClickListener(v->scan());
        gap(control,7);
        Button system=button("Системное управление Bluetooth",false);control.addView(system,lp(-1,50));
        system.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        gap(control,8);
        control.addView(text("Принудительное подключение HFP стороннему приложению недоступно. Кнопки запускают только разрешённые Android действия.",12,MUTED,false));
        gap(root,12);
        LinearLayout logCard=card(root);
        logCard.addView(text("Диагностика",18,WHITE,true));gap(logCard,8);
        caption=text("Запиши попытку подключения и отметь спиннер PCM.",13,MUTED,false);
        logCard.addView(caption);gap(logCard,10);
        record=button("●  Начать запись",true);logCard.addView(record,lp(-1,54));
        record.setOnClickListener(v->toggleCapture());gap(logCard,8);
        Button mark=button("В PCM крутится спиннер",false);
        logCard.addView(mark,lp(-1,50));
        mark.setOnClickListener(v->mark());
        gap(logCard,14);
        timeline=text("Событий пока нет",12,MUTED,false);
        timeline.setLineSpacing(dp(3),1.04f);logCard.addView(timeline);gap(root,12);
        Button export=button("Экспорт JSON-отчёта",false);root.addView(export,lp(-1,52));
        export.setOnClickListener(v->export());
        gap(root,12);
        root.addView(text("Локально · без интернета · без контактов и содержимого звонков. PCM6 не изменяется.",12,MUTED,false));
    }
    private String currentMac(){BluetoothDevice d=selected();return d==null?"":d.getAddress();}
    private BluetoothDevice selected(){
        int p=spinner==null?-1:spinner.getSelectedItemPosition();
        return p<0||p>=devices.size()?null:devices.get(p);
    }
    private boolean contains(String mac){
        for(BluetoothDevice d:devices)if(mac.equals(d.getAddress()))return true;
        return false;
    }
    private void loadBonded(){
        if(!permitted()||adapter==null||spinner==null)return;
        String old=currentMac();
        if(old.isEmpty())old=LogStore.prefs(this).getString(LogStore.MAC,"");
        try{
            for(BluetoothDevice d:adapter.getBondedDevices())
                if(!contains(d.getAddress()))devices.add(d);
        }catch(Exception ignored){}
        devices.sort((x,y)->{
            String a=x.getName()==null?"":x.getName().toLowerCase(Locale.ROOT);
            String b=y.getName()==null?"":y.getName().toLowerCase(Locale.ROOT);
            boolean ap=a.contains("cayenne")||a.contains("porsche")||a.contains("pcm")||a.contains("macan");
            boolean bp=b.contains("cayenne")||b.contains("porsche")||b.contains("pcm")||b.contains("macan");
            if(ap!=bp)return ap?-1:1;return a.compareTo(b);
        });
        updateSelector(old,true);
    }
    private void updateSelector(String prefer,boolean quiet){
        if(spinner==null)return;
        ArrayList<String> labels=new ArrayList<>();
        for(BluetoothDevice d:devices){
            String name="Неизвестное устройство";
            try{if(d.getName()!=null&&!d.getName().isEmpty())name=d.getName();}catch(Exception ignored){}
            String mac=d.getAddress();String suffix=mac.length()>5?mac.substring(mac.length()-5):"";
            labels.add(name+"  · "+suffix);
        }
        if(labels.isEmpty())labels.add("Нет найденных устройств");
        ArrayAdapter<String> adapterUi=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){
            @Override public View getView(int pos,View c,ViewGroup parent){
                TextView t=(TextView)super.getView(pos,c,parent);t.setTextColor(WHITE);
                t.setTextSize(15);t.setPadding(dp(9),dp(12),dp(9),dp(12));return t;
            }
            @Override public View getDropDownView(int pos,View c,ViewGroup parent){
                TextView t=(TextView)super.getDropDownView(pos,c,parent);
                t.setTextColor(WHITE);t.setBackgroundColor(CARD);t.setPadding(dp(12),dp(13),dp(10),dp(13));return t;
            }
        };
        adapterUi.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapterUi);
        if(prefer!=null)for(int i=0;i<devices.size();i++){
            if(prefer.equalsIgnoreCase(devices.get(i).getAddress())){spinner.setSelection(i);break;}
        }
    }
    private void refresh(){
        if(status==null)return;
        BluetoothDevice d=selected();
        if(!permitted()){status.setText("Нужно разрешение «Устройства поблизости».");return;}
        if(adapter==null||!adapter.isEnabled()){
            status.setText("Bluetooth выключен. Включи его через системные настройки.");return;
        }
        if(d==null){status.setText("Выбери Porsche в списке.");return;}
        try{
            String bond=CaptureService.bond(d.getBondState());
            String call=hfp==null?"Нет данных":CaptureService.state(hfp.getConnectionState(d));
            String audio=a2dp==null?"Нет данных":CaptureService.state(a2dp.getConnectionState(d));
            status.setText("☎  Звонки / HFP:  "+call+"\n♫  Музыка / A2DP:  "+audio+
                "\n◈  Сопряжение:  "+bond);
        }catch(Exception e){status.setText("Bluetooth: "+e.getClass().getSimpleName());}
        boolean running=LogStore.isRecording(this);
        if(record!=null){
            record.setText(running?"■  Остановить запись":"●  Начать запись");
            record.setTextColor(running?WHITE:BG);
            record.setBackground(background(running?0xFF43546C:BLUE,12,false));
        }
        if(caption!=null)caption.setText(running?"Запись идёт. Повтори подключение на PCM.":"Запись остановлена. Можно экспортировать отчёт.");
        JSONArray ev=LogStore.events(this);
        if(timeline!=null){
            StringBuilder text=new StringBuilder();
            for(int i=Math.max(0,ev.length()-6);i<ev.length();i++){
                JSONObject e=ev.optJSONObject(i);if(e==null)continue;
                String at=e.optString("at");if(at.length()>=19)at=at.substring(11,19)+" UTC";
                String detail=e.optString("detail");
                if(detail.length()>88)detail=detail.substring(0,88)+"…";
                text.append(at).append("  ").append(e.optString("type")).append("\n").append(detail).append("\n\n");
            }
            timeline.setText(text.length()==0?"Событий пока нет":text.toString().trim());
        }
    }
    private void updateServices(){
        if(services==null)return;
        BluetoothDevice d=selected();
        if(d==null||!permitted()){services.setText("Выбери устройство");return;}
        try{
            ParcelUuid[] uu=d.getUuids();
            services.setText((uu==null||uu.length==0?"Сервисы пока не получены":CaptureService.services(uu))+
                "\nСписок SDP — не доказательство успешного подключения HFP.");
        }catch(Exception e){services.setText("SDP: "+e.getClass().getSimpleName());}
    }
    private void requestSdp(){
        BluetoothDevice d=selected();
        if(!permitted()||d==null){requestPermissionsIfNeeded();return;}
        try{
            boolean started=d.fetchUuidsWithSdp();
            if(LogStore.isRecording(this))LogStore.append(this,"SDP_REQUEST","started="+started);
            Toast.makeText(this,started?"Запрос SDP отправлен":"Android не смог начать SDP",Toast.LENGTH_LONG).show();
            updateServices();
        }catch(Exception e){show("Не удалось запросить SDP: "+e.getMessage());}
    }
    private void pair(){
        BluetoothDevice d=selected();
        if(d==null||!permitted()){requestPermissionsIfNeeded();return;}
        try{
            if(d.getBondState()==BluetoothDevice.BOND_BONDED){
                new AlertDialog.Builder(this).setTitle("Уже сопряжено")
                    .setMessage("Android считает устройство сохранённым. Для переподключения профиля HFP открой системные настройки. Обычное приложение не может принудительно включать HFP.")
                    .setPositiveButton("Настройки",(a,b)->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("Отмена",null).show();
            }else{
                boolean started=d.createBond();
                if(LogStore.isRecording(this))LogStore.append(this,"PAIR_REQUEST","started="+started);
                Toast.makeText(this,started?"Ожидай запрос Android на сопряжение":"Сопряжение не запущено",Toast.LENGTH_LONG).show();
            }
        }catch(Exception e){show("Не удалось начать сопряжение: "+e.getMessage());}
    }
    private void scan(){
        if(!canScan()||!permitted()){requestPermissionsIfNeeded();return;}
        if(adapter==null)return;
        try{
            if(adapter.isDiscovering()){adapter.cancelDiscovery();scan.setText("Сканировать устройства");}
            else {
                boolean ok=adapter.startDiscovery();
                scan.setText(ok?"Остановить сканирование":"Сканировать устройства");
                Toast.makeText(this,ok?"Поиск на 12 секунд":"Не удалось начать поиск",Toast.LENGTH_SHORT).show();
                if(ok)main.postDelayed(()->{
                    try{
                        if(canScan()&&adapter!=null&&adapter.isDiscovering())adapter.cancelDiscovery();
                    }catch(Exception ignored){}
                    scan.setText("Сканировать устройства");
                },12000);
            }
        }catch(Exception e){show("Поиск не запущен: "+e.getMessage());}
    }
    private void toggleCapture(){
        if(!permitted()){requestPermissionsIfNeeded();return;}
        if(LogStore.isRecording(this)){
            Intent i=new Intent(this,CaptureService.class);i.setAction(CaptureService.ACTION_STOP);
            startService(i);
        }else {
            BluetoothDevice d=selected();if(d==null){show("Сначала выбери устройство");return;}
            if(adapter==null||!adapter.isEnabled()){show("Включи Bluetooth");return;}
            Intent i=new Intent(this,CaptureService.class);i.setAction(CaptureService.ACTION_START);
            i.putExtra(CaptureService.EXTRA_MAC,d.getAddress());
            String name=d.getName();i.putExtra(CaptureService.EXTRA_NAME,name==null?"Porsche":name);
            try{startForegroundService(i);}catch(Exception e){show("Запись не запущена: "+e.getMessage());}
        }
        main.postDelayed(this::refresh,300);
    }
    private void mark(){
        if(!LogStore.isRecording(this)){show("Сначала нажми «Начать запись»");return;}
        Intent i=new Intent(this,CaptureService.class);i.setAction(CaptureService.ACTION_MARK);
        startService(i);
        Toast.makeText(this,"Момент спиннера отмечен",Toast.LENGTH_SHORT).show();
        main.postDelayed(this::refresh,300);
    }
    private void export(){
        if(LogStore.events(this).length()==0){show("Сначала запиши попытку подключения");return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE,"PCM_Link_Bluetooth_"+Build.MODEL.replace(' ','_')+".json");
        startActivityForResult(i,REQ_EXPORT);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==REQ_EXPORT&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
                if(out==null)throw new IllegalStateException("null stream");
                out.write(LogStore.export(this).toString(2).getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this,"Отчёт сохранён",Toast.LENGTH_LONG).show();
            }catch(Exception e){show("Ошибка экспорта: "+e.getMessage());}
        }
    }
    private void show(String message){new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK",null).show();}
}
