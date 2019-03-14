package com.p1.genius.bluetoothconectionapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.maps.android.PolyUtil;
import com.google.maps.model.DirectionsResult;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;


/**
 * 導航頁面的程式碼
 * 只有在藍芽連線建立成功後才會被開啟，
 * 若藍芽斷線則會關閉畫面，回到藍芽連線畫面
 * 但是可透過連線畫面中的"BACK DOOR"按鈕，在不建立藍芽連線的情況下進入此畫面。
 *
 * 而我們曾試圖增加"不正常斷線後得自動重新連線"功能，使機車騎士不需在斷線後還得停車重手動連線
 * 卻礙於時間限制只完成了部分程式碼，剩下的工作就寄託在你們身上了
 * (自動連線有很多方式，我選了一個不是很好的。所以你們可以自行決定要沿用或者直接砍掉重寫)
 */

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener
{
    // 有些class member會被Android Studio 反白，
    // 原因是Android Studio 判定這些member可被某個方法中，成為區域變數節省記憶體資源
    // 然而不確定未來是否會做更多擴充，所以我們暫時不處理(你們決定就好)

    private String debug = "Direction debug";    // Android device 中系統logcat 的標籤(測試或除錯用途)
    private String Device;    // 藍芽連線Server端的藍芽實體位置
    private Boolean TTR = false;    // 自動重新連線需用到的flag (暫時無用)
    private LocationManager locationManager;    // 定位服務管家，其實就是個調用定位服務(取得使用者座標等等)的介面
    private String provider;    // 定位服務提供者(不見得是GPS，也可由行動通訊基地台，或者wifi網路提供)
    private boolean locationUpdateRequested = false;    // "是否啟用座標偵測及更新"的flag(如果已啟用，應用程式結束時就要關掉)
    private boolean isConnected = true;    // "藍芽是否正常連線"的flag
    private GoogleApiClient mapClient;    // 取得Google 各樣服務的介面(已經被捨棄，需修改)
    private GoogleMap mMap;    // 地圖本人，多說無益
    private Marker desMark;    // 目的地標示
    private Button bStart;    // 按下去之後就會開始導航喔
    private Button bStop;    // 按下去之後就會結束導航喔
    private LatLng mLocation;    // 使用者座標
    private LatLng desLocation;    // 目的地座標
    private GuidingThread mGuidingThread;    // 負責導航任務的執行緒
    private static final int REQUEST_LOCATION = 2;    // 權限的請求代碼
    private BluetoothController mController = null;    // 就是用來那個那個的BluetoothController物件
    private BroadcastReceiver BTreceiver = null;    // 為了自動重新連線而存在的廣播接收器
    static class mHandler extends Handler    // 接收訊息(座標變更、藍芽連線狀況等訊息)的handler 類別
    {
        private final WeakReference<MapsActivity> mactivity;

         mHandler(MapsActivity activity)
        {
            mactivity = new WeakReference(activity);
        }

        @Override
        public void handleMessage(Message msg)    // Handler定義跟MainActivity差不多
        {
            super.handleMessage(msg);
            MapsActivity activity = mactivity.get();

            switch (msg.what)
            {
                case Constants.STATE__RECONNECTED:
                    activity.setState(true);
                    break;
                case Constants.DESTINATION_REACHED:    // 抵達目的地之後要告知Arduino 端，目前只有在手機內顯示(這部分交給你們了)
                    Toast.makeText(activity, "Destination reached.", Toast.LENGTH_LONG).show();
                    break;
                case Constants.GUIDING_ERROR:
                    activity.mGuidingThread = null;
                    activity.nailedIt();
                    break;
                case Constants.NEW_RESULT:
                    activity.makeItVisible(activity.mGuidingThread.getmResult());
                    break;
                case MessageConstants.MESSAGE_READ:    // 若有需要從Arduino 端接收訊息，以下程式碼請自取(不敢保證是否能運作)
                    /*String readMessage = "";
                    try
                    {
                        readMessage = new String((byte[]) msg.obj, "UTF-8");
                        _recieveData = readMessage; //拼湊每次收到的字元成字串
                    }
                    catch (UnsupportedEncodingException e)
                    {
                        e.printStackTrace();
                    }
                    Toast.makeText(MainActivity.this, _recieveData, Toast.LENGTH_SHORT).show();
                    recieveText.setText(_recieveData);*/
                    break;
                case MessageConstants.MESSAGE_WRITE:
                    String instr = (String) msg.obj;
                    Log.d(activity.debug,"Handler:" + instr);
                    if (activity.mController != null)
                        activity.mController.sendInstructions(instr);
                    Toast.makeText(activity, instr, Toast.LENGTH_LONG).show();
                    break;
                case MessageConstants.MESSAGE_TOAST:
                    String error = msg.getData().getString("toast", "");
                    Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                    break;
                case MessageConstants.MESSAGE_STATE_CHANGE:
                    switch (msg.arg1)
                    {
                        case Constants.STATE_CONNECT_FAILED:
                            Toast.makeText(activity, "連線已斷開，請重新連接", Toast.LENGTH_SHORT).show();
                            //activity.finish(); 20190113
                            break;
                    }
                    break;
            }
        }
    }
    private final mHandler mHandler = new mHandler(this);    // 上面是類別，這裡是實做出來的物件


    /**
     *  MapsActivity的生命週期回呼函式如下:
     */
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Bundle bundle =this.getIntent().getExtras();
        Device = Objects.requireNonNull(bundle).getString("device");
        //20190113
        if(!"".equals(Device))    // if判斷式是為了後門而存在的
        {
            TTR = bundle.getBoolean("try_to_reconnect");    // 判斷是否因自動重新連線成功後開啟(自動連線功能尚未實作)
            mController = BluetoothController.getInstance(mHandler);

            BTreceiver = new BroadcastReceiver()    //實作廣播接收器偵測藍芽連線狀況，如果斷線就會呼叫BluetoothController中的connectionLost()方法
            {
                @Override
                public void onReceive(Context context, Intent intent)
                {
                    setState(false);
                    if(mController != null)
                        mController.connectionLost();
                }
            };
            if(mController != null)
            {
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                registerReceiver(BTreceiver, filter);
            }
        }



        /*
        * 以下是Android 取用Google地圖的初始設定(不重要，不用研究)
        * 但假如哪天地圖突然沒辦法顯示，記得檢查這裡，可能有些方法已經失效(Google是善變的)
        */
        setContentView(R.layout.activity_maps);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        provider = locationManager.getBestProvider(criteria, true);

        mapClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
        mapClient.connect();

        /*
        * 取得Activity存放之各原件的記憶體位置
        */
        bStart = findViewById(R.id.start);
        bStop = findViewById(R.id.stop);
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        // 底下這坨被封印的兩行也是為了後門，解除封印的話後門就廢了。
        /*if(mController.getState() != Constants.STATE_CONNECTED)
            finish();*/
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onResume()
    {
        super.onResume();
        // 底下這坨跟自動連線功能有關
      /*  if(locationUpdateRequested)
            locationManager.requestLocationUpdates(provider, 1000, 1,this);*/
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        if(mController!=null)
            mController.stop();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();

        /*
         * MapsActivity銷毀前，有些綁定的功能要先解除
         * (銷毀運作中的執行緒、解除座標更新等等)
         */
        if (locationUpdateRequested)
        {
            locationUpdateRequested = false;
            locationManager.removeUpdates(this);
        }
        if (mGuidingThread != null)
            mGuidingThread.cancel();
        mapClient.disconnect();
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     * (這是Android Studio內建的貼心註解，不是我寫的)
     */
    @Override
    public void onMapReady(GoogleMap googleMap)
    {
        mMap = googleMap;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            // TODO: Consider calling
            // (大寫的todo通常表示"待實作的功能描述"，在團隊接力完成一個project時常出現
            // 有點"這個爛攤子交給你了"的感覺。底下的功能描述已經被實作了，但我們覺得這
            // 種正統的註解寫法蠻有參考價值的，所以保留下來。)
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_LOCATION);
        }
        else {
            setupMyLocation();
        }
    }

    @SuppressLint("MissingPermission")
    private void setupMyLocation()    // "取用使用者座標"相關設定
    {
        mMap.setMyLocationEnabled(true);
        mMap.setOnMyLocationButtonClickListener(new GoogleMap.OnMyLocationButtonClickListener()    // 實做地圖右下方定位按鈕的onClick監聽器
        {
            @Override
            public boolean onMyLocationButtonClick()    // 按下按鈕後會執行的工作(可自行修改)
            {
                /*
                * 首先確定定位服務是可取得的，
                * 再來，依據使用者目前座標，以動畫方式，
                * 將地圖畫面移動至座標所在之處，
                * 再zoom in ，顯示較詳細的資訊。
                * (表達能力不好，如果看不懂我在說甚麼就直接打開手機的Google maps APP，
                *  會看到一樣的按紐，直接按按看就明白了。)
                */
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                {
                    Location location = locationManager.getLastKnownLocation(provider);
                    if (location != null)
                    {
                        mLocation = new LatLng(location.getLatitude(), location.getLongitude());
                        desLocation = mLocation;
                        // 這裡就是把地圖畫面當作攝影機鏡頭，設定一些動畫參數(焦點座標、Zoom in 速度等)
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                new LatLng(location.getLatitude(), location.getLongitude()),
                                15));
                        if(mGuidingThread == null)    // 若無進行中的導航工作，就在焦點(使用者所在位置)座標放置大頭針
                        {
                            nailedIt();
                        }
                    }
                }
                else
                    Toast.makeText(MapsActivity.this, "定位未開啟", Toast.LENGTH_LONG).show();

                // false是預設的return值，有需要可以自行更改
                return false;
            }

        });
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)    // 定位權限request的回呼函式
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode)
        {
            case REQUEST_LOCATION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)    // 若使用者允許取用定位資訊(給了權限)，則呼叫setupMyLocation()
                {
                    setupMyLocation();
                }
                else
                {
                    Toast.makeText(this, "不給權限就不要用啊:-|", Toast.LENGTH_LONG).show();
                }
                break;
        }
    }

    public void navStart(View v)    // "NAVIGATION STAR"按鈕被按下之後，開始導航
    {
        if (mGuidingThread == null)
        {
            // 底下這坨只是再次確認是否取得定位權限，不這麼做會被Android Studio 用反白，看了很討厭
            if (
                    ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            locationManager.requestLocationUpdates(provider, 1000, 1, MapsActivity.this);    // 設定定期定位更新(這個指令目前失去作用，導航功能失效，
                                                                                                                              // 原因不明確。)

            locationUpdateRequested = true;    // 立"導航正在進行"flag，在關閉MapsActivity 時，需先檢查此flag免得導航執行緒留在系統中繼續執行，浪費系統資源。
            mGuidingThread = GuidingThread.getGuidingInstance(mLocation, desLocation, mHandler);    // 取得導航執行緒物件(參照)
            mGuidingThread.start();    // 開始導航
        }
        else
            Log.d(debug, "Task has already started!");
    }

    public void navStop(View v)    // 取消導航
    {
        if (mGuidingThread != null)
        {
            mGuidingThread.cancel();
            mGuidingThread = null;
            nailedIt();
            if (locationUpdateRequested)
            {
                locationUpdateRequested = false;
                locationManager.removeUpdates(this);
            }
        }
    }


    public synchronized void setState(boolean state)    // isConnected 的set 方法
    {
        isConnected = state;
    }


    private void nailedIt()    // 在使用者座標放置大頭針
    {
        mMap.clear();    // 先將地圖標示清空
        desMark = mMap.addMarker(new MarkerOptions()
                .position(mLocation)
                .draggable(true));    // 設定大頭針可被拖移
        mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener()
        {
            /*
            * 可以在這裡指定大頭針移動過程執行的工作
            */
            @Override
            public void onMarkerDragStart(Marker marker) { }

            @Override
            public void onMarkerDrag(Marker marker) { }

            @Override
            public void onMarkerDragEnd(Marker marker) {
                desLocation = marker.getPosition();
            }
        });
    }


    /////////////////////////////////////////////   Google API 連線設定  /////////////////////////////////////////////
    /**
     *  建立與Google Location Service 伺服器連線的程式碼。
     *  Google Location Service 功能與Android 內鍵Location Manager 差不多(好像又更強吧)，
     *  而且不因受ndroid 作業系統版本差異的影響(有的程式碼run在不同版本的系統上，會有非常不同的結果。很神奇對吧!)。
     *  但我不知道如何只使用Google Location Service 就好(節省系統資源)，所以保留兩種方式。
     *  建議日後留下一個當作主力。
     */

    @SuppressLint("MissingPermission")
    @Override
    public void onConnected(@Nullable Bundle bundle)
    {
        FusedLocationProviderClient fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this.getApplicationContext());
        fusedLocationProviderClient.getLastLocation().addOnCompleteListener(this, new OnCompleteListener<Location>()
        {
            @Override
            public void onComplete(@NonNull Task<Location> task)
            {
                Location location = task.getResult();
                if (location != null)
                {
                    mLocation = new LatLng(location.getLatitude(), location.getLongitude());
                    desLocation = mLocation;
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(location.getLatitude(), location.getLongitude()),
                            15));
                    nailedIt();
                }
            }
        });
    }

    //////////////////////////////////////  LocationListener 介面  /////////////////////////////////////////////

    /**
     *  MapsActivity同時也實作了 LocationListener 介面(他實作了好多介面)，
     * 一些需被覆寫的方法寫在這裡。
     * 我們只有時間撰寫最基本的部分，很多東西來不及加(深感抱歉)。
     */

    @Override
    public void onConnectionSuspended(int i) { }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) { }

    @Override
    public void onLocationChanged(Location location)    // 使用者座標變動時的call back function，一樣會出現動畫然後加上大頭針
    {
        mLocation = new LatLng(location.getLatitude(), location.getLongitude());
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(location.getLatitude(), location.getLongitude()),
                15));
        if(mGuidingThread != null)
            mGuidingThread.setLocationChanged(mLocation);
        Log.d(debug, "Location Changed!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    //////////////////////////////////////////////   導航標示 code   /////////////////////////////////////////////

    /**
     *  解讀Direction result，並在地圖上標示出完整路徑的方法們。
     *  我們將流程拆成三個部分 : "取得終點座標&資訊"、"在路徑頭尾兩端點加上標示"、"路徑以粗線條標示"，
     *  寫成三個獨立的函式。
     *  上述函式會在makeItVisible()方法內被呼叫。
     *  目的是切割程式碼，讓它更好寫、好讀、好維護。
     */

    private void makeItVisible(DirectionsResult result)    // 以DirectionsResult(路徑資料包)參照當作引數
    {
        mMap.clear();
        if (result == null)
        {
            Toast.makeText(this, "Null result", Toast.LENGTH_SHORT).show();
        }
        else {
            addMarkersToMap(result);
            addPolyline(result);
        }
    }

    private void addMarkersToMap(DirectionsResult results)
    {
        mMap.addMarker(new MarkerOptions().position(new LatLng(results.routes[0].legs[0].startLocation.lat, results.routes[0].legs[0].startLocation.lng))
                .title(results.routes[0].legs[0].startAddress));
        desMark = mMap.addMarker(new MarkerOptions().position(new LatLng(results.routes[0].legs[0].endLocation.lat, results.routes[0].legs[0].endLocation.lng))
                .title(results.routes[0].legs[0].startAddress).snippet(getEndLocationTitle(results)));
    }

    private String getEndLocationTitle(DirectionsResult results)
    {
        return "Time :" + results.routes[0].legs[0].duration.humanReadable + " Distance :" + results.routes[0].legs[0].distance.humanReadable;
    }

    private void addPolyline(DirectionsResult results)
    {
        List<LatLng> decodedPath = PolyUtil.decode(results.routes[0].overviewPolyline.getEncodedPath());
        mMap.addPolyline(new PolylineOptions().addAll(decodedPath));
    }
}


/////////////////////////////////////////  連線用 AsyncTask  //////////////////////////////////////////////
/**
 *  曾經嘗試用輕量級的執行緒 AsyncTask 執行導航工作，
 *  雖然好寫，卻不適合執行重量級工作。
 *  日後若需要新增執行緒，工作內容負荷較輕
 *  則可以考慮使用AsyncTask。
 */

    /*class DirRequestTask extends AsyncTask<Void, Void, Void>
    {
        @Override
        protected Void doInBackground(Void... params)
        {
            String urlrqst = getRequestUrl(mLocation, desLocation);
            StringBuilder sb = new StringBuilder();
            try
            {
                URL url = new URL(urlrqst);
                BufferedReader read = new BufferedReader(new InputStreamReader(url.openStream()));
                String line = read.readLine();
                while(line != null)
                {
                    Log.d("direction JSON:", line);
                    sb.append(line + "\n");
                    line = read.readLine();
                }
                //Log.d("DIRECTION APP", sb.toString());
            }
            catch (MalformedURLException e)
            {
                e.printStackTrace();
                Log.e("Direction Eror",e.getMessage());Log.e("Direction Eror",e.getMessage());Log.e("Direction Eror",e.getMessage());
            }
            catch (IOException e)
            {
                Log.e("Direction Eror",e.getMessage());Log.e("Direction Eror",e.getMessage());Log.e("Direction Eror",e.getMessage());
            }

            return null;
        }

        private String getRequestUrl(LatLng org, LatLng dst)
        {
            String strOrg = "origin=" + org.latitude + "," + org.longitude;
            String strDst = "destination=" + dst.latitude + "," + dst.longitude;
            String mode = "travelMode=DRIVING";
            String highways = "avoidHighways=true";
            String param = strOrg + "&" + strDst + "&" + mode + "&" + highways + "&key=" + key;
            String url = "https://maps.googleapis.com/maps/api/directions/json?" + param;
            return url;
        }
    }*/
