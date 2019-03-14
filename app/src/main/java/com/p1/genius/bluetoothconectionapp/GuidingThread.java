package com.p1.genius.bluetoothconectionapp;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import com.google.maps.DirectionsApi;
import com.google.maps.GeoApiContext;
import com.google.maps.errors.ApiException;
import com.google.maps.model.DirectionsLeg;
import com.google.maps.model.DirectionsResult;
import com.google.maps.model.DirectionsStep;
import com.google.maps.model.Distance;
import com.google.maps.model.TravelMode;
import com.google.maps.model.Unit;
import com.google.cloud.translate.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 針對"導航"工作特別設計的執行緒類別。
 */

class GuidingThread extends Thread
{
    private boolean Trans;
    private String debug = "Direction debug";
    private String key = "AIzaSyAHG_MzhRM-5IsfnKDi-cqKT7VS4ssv64Q";
    private Handler mHandler;
    private boolean locationChanged = false;
    private boolean paused = false;
    private boolean canceled = false;
    private LatLng origLocation;
    private LatLng mLocation;
    private LatLng desLocation;
    private DirectionsResult mResult;
    private static GuidingThread guiding = null;


    // GuidingThread 的建構子，以"起點座標"、"終點座標"、"MapsActivity 的Handler"為引數，並且設定為private，無法從外部任意呼叫
    private GuidingThread(LatLng location, LatLng location2, Handler handler)
    {
        origLocation = location;
        mLocation = origLocation;
        desLocation = location2;
        mHandler = handler;
    }

    // GuidingThread 的GetInstance方法，用以控制物件被實作的數量。並且為了資料同步，加上了synchronized 鎖。
    static synchronized GuidingThread getGuidingInstance(LatLng initial, LatLng destination, Handler handler)
    {
        if(guiding == null)
            guiding = new GuidingThread(initial, destination, handler);
        else {
            guiding.mHandler = handler;
            guiding.mResume();
        }
        return guiding;
    }

    public void run()    // 執行緒的工作內容(廢話。)
    {
        Log.d(debug, "Guiding start!!!!!!!!!!!!!!!!!!!!!!");
        paused = false;
        if (desLocation.longitude != mLocation.longitude && desLocation.latitude != mLocation.latitude)
        {
            DirectionsResult result = getResult();
            if (result != null)
            {
                Message msg = mHandler.obtainMessage(Constants.NEW_RESULT);
                msg.sendToTarget();
                Guide(result);
            }
            else {
                Log.d(debug, "No result");
            }
        }
        guiding = null;
    }


    private void Guide(DirectionsResult result)    // 導航工作真正被定義的地方(五星級重要)
    {
        /*
        * DirectionsResult可以被切割成一個或多個"leg"，
        * leg又可以再切成一個或多個"step"。
        * leg中包含了此leg的距離、頭尾座標、包含的step個數、預估需花費的時間......;
        * step中包含了此step的距離、頭尾座標。
        *
        * DirectionResult的真面目: https://maps.googleapis.com/maps/api/directions/json?origin=Taichung&destination=Taipei&key=AIzaSyAHG_MzhRM-5IsfnKDi-cqKT7VS4ssv64Q
        * 這是從台中到台北的路徑request，把網址複製到瀏覽器就看得到了。
        * 請對照指令結構，才能看懂指令如何被拆解，還有底下的程式編寫邏輯。
        * 其中也可以看看，透過https向伺服器溝通(request)使用的符號 (?後放的是request的參數，不同參數之間用&連接，=後則是參數賦予的值)。
        */

        List<DirectionsLeg> legs = new ArrayList<>(Arrays.asList(result.routes[0].legs));    // 將DirectionsResult中的所有legs，存入List(資料結構為array)
        Iterator<DirectionsLeg> legsIT = legs.listIterator();
        InstructionDecode decoder = new InstructionDecode();    // 實作了自定義的decoder類別(祥見底下的InstructionDecode 類別)

        // 當初不知道有個東西叫做Looper萬不得已才用while寫(效能爆炸爛又超級耗電)，這裡一定要改寫
        while (legsIT.hasNext() && !canceled)    // 先確認是否有下一步，或者是否已取消導航
        {
            DirectionsLeg currentLeg = legsIT.next();
            List<DirectionsStep> steps = new ArrayList<>(Arrays.asList(currentLeg.steps));
            Iterator<DirectionsStep> stepsIT = steps.listIterator();

            DirectionsStep currentStep = stepsIT.next();
            DirectionsStep nextStep = null;
            do    //  這裡使用do-while迴圈，否則最後一個step永遠會被跳過(沒有nextStep，直接離開迴圈)
            {
                /*
                 * 底下開始出現各種混亂的判斷式，
                 * 有些判斷式不是很直覺，也是經過多次失敗才慢慢加進去的
                 * 所以看不懂的話就算了。
                 */
                if(stepsIT.hasNext())    // 預先取出nextStep，才能知道下一步要直走、右轉、左轉......
                    nextStep = stepsIT.next();
                com.google.maps.model.LatLng currentDestination = currentStep.endLocation;    // 取得currentStep的終點座標
                String instruction = currentStep.htmlInstructions;    // 取得currentStep的HTML指令(傳給Arduino的資訊主要從這裡擷取)
                Log.d(debug, "html指引: "+instruction);
                Distance stepDistance = currentStep.distance;
                /*
                * 預先將nextStep解讀，從中擷取所需的資訊。
                * 因我們在安全帽上所呈現的的指令格式或內容為，舉例來說: 在前方100公尺處向左轉到忠明南路上 (假設使用者目前位於台灣大道二段)
                * 然而，currentStep中的html_instructions格式卻是: 於<b>台灣大道二段</b>向<b>左</b>轉，distance為: 100 m
                * 也就是說，我們所呈現的每一個指令，其實是兩個連續指令的各半。
                * 所以需要兩個step，才能拼湊出我們所需的完整指令。
                */
                String nextTmp = decoder.Classified( Objects.requireNonNull(nextStep).htmlInstructions);
                String nextAction;
                // 我不記得為什麼要做這個判斷了，應該跟html_instructions格式的種類有關
                if(nextTmp.charAt(0)=='2' || nextTmp.charAt(0)=='3')
                    nextAction = nextTmp;
                else
                    nextAction = nextTmp.charAt(0) + "";

                // 拼湊出預備傳給Arduino的指令: distance + next action + 路名之類的雜七雜八資訊 + 跳脫字元(/0)
                String decodedInstr = "" +stepDistance.humanReadable + "+" + nextAction + "+" + decoder.Classified(instruction) + "/0";
                Log.d(debug, instruction);
                // 同時，要隨時計算"距離下一個步驟還有多遠"。
                // 還要確任使用者是否正朝著正確方向前進，方法就是比較distanceLeft的變化(方向對的話就應該越來越小)
                // 因此，需要一個空間tmp暫存前一次更新的distanceLeft值(只有一個值沒辦法比較)。
                // 可以找找Google Location API，或許存在更棒的方式可以直接用幾個函式簡單解決。
                double distanceLeft = stepDistance.inMeters;
                double tmp2 = distanceLeft;
                Log.d(debug, "Step distance: " + stepDistance.inMeters);
                mHandler.obtainMessage(MessageConstants.MESSAGE_WRITE, decodedInstr).sendToTarget();

                // targetReached是用來判別是否抵達step末端的flag。
                // Atomic的用圖類似synchronized，是特別針對基本資料型態同步鎖(不確定這樣解釋好不好)。
                // 兩種宣告各有其用優勢，會直接影響執行效能(詳細內容請自行Google)。
                // 這裡的targetReached其實不需做Atomic宣告，只是想告訴你們資料同步的另一個option。
                AtomicBoolean targetReached = new AtomicBoolean(false);


                while(!targetReached.get() && !canceled)    // 這個迴圈的條件算易讀，不(懶得)解釋
                {
                    Log.d(debug, instruction);
                    Log.d(debug, decodedInstr);
                    while(!locationChanged || paused)    // 我覺得這個迴圈有點暴力，不曉得用中斷是否比較有效率(其實看要location更新的頻率然後分析。我不知道怎麼分析:-|)
                    {
                        // Wait until location update
                        // Wait until restart
                        if(canceled) return;
                    }
                    locationChanged = false;
                    double tmp = getDistance(currentDestination, mLocation);    // 計算"step終點座標"跟"使用者目前座標"之間的距離

                    if(tmp > distanceLeft+8)
                        // 如果tmp比原本的distanceLeft還多8公尺，及判定使用者行駛方向錯誤，並丟出警告(Arduino端尚未實作此功能)
                    {
                        String alert = "Wrong direction!!!";
                        Log.d(debug, "distanceLeft: " + distanceLeft);
                        Log.d(debug, "tmp: " + tmp);
                        Log.d(debug, alert);

                        if(tmp > (stepDistance.inMeters+30))    // 如果已經超出30公尺就中斷導航，重新向Google伺服器請求路徑，重新開始導航
                        {
                            Log.d(debug, "distanceLeft: " + distanceLeft);
                            Log.d(debug, "tmp: " + tmp);
                            Log.d(debug, "Request new Result");
                            DirectionsResult tmpResult = getResult();    // 嘗試取得新的DirectionsResult
                            if (tmpResult != null)    // 確認DirectionsResult不是null
                            {
                                mHandler.obtainMessage(Constants.NEW_RESULT).sendToTarget();
                                Guide(tmpResult);    // 呼叫自己，重新開始導航
                            }
                            else
                            {
                                // 若無法取得DirectionsResult，就那個那個
                                Log.d(debug, "No result");
                                mHandler.obtainMessage(Constants.GUIDING_ERROR).sendToTarget();
                            }
                            return;
                        }
                    }
                    else
                    {
                        distanceLeft = tmp;
                        Log.d(debug, "distanceLeft: " + distanceLeft);
                        if((tmp2 - distanceLeft) > 10)    // 我真的看不懂當初為甚麼要加tmp2跟這個判斷式了...可以試著拿掉看看會不會出事
                        {
                            Log.d(debug, "New distanceLeft: " + distanceLeft);
                            tmp2 = distanceLeft;
                        }
                    }

                    // 如果和step終點距離少於6公尺，就判定抵達目標，可以執行下一個step
                    // 然而這裡其實應該以中斷的方式執行，因迴圈可能有誤判的風險(比如車速太快，座標來不及更新)
                    // 建議研究看看中斷的可行性，或者參考他人的作法。
                    targetReached.set(distanceLeft < 6);
                    if(targetReached.get())
                    {
                        currentStep = nextStep;
                        Log.d(debug, "Target reached");
                    }
                }
            } while (stepsIT.hasNext() && !canceled);
        }
        mHandler.obtainMessage(Constants.DESTINATION_REACHED).sendToTarget();
    }


    private DirectionsResult getResult()    // No free lunch. 要取得DirectionResult，就要向Google伸手請求。
    {
        DirectionsResult result = null;
        try
        {
            // 上面有提過，以HTTPS向Google伺服器發出請求，url 中的?後面可以加上Request的參數。撰寫Android APP，
            // 則可以使用Google提供的DirectionsApi設定參數(當然也可以自行建立https連線，然後傳送url取得result。
            // 步驟其實類似建立藍芽連線)。能設定的參數不只這些，可以自行上網查看Google官方文件。
            result = DirectionsApi.newRequest(getGeoApiContext())
                    .origin(new com.google.maps.model.LatLng(origLocation.latitude, mLocation.longitude))
                    .destination(new com.google.maps.model.LatLng(desLocation.latitude, desLocation.longitude))
                    .mode(TravelMode.WALKING)    // Google地圖已經有"摩托車"這個選項，只是目前尚未開放平民老百姓使用，可以隨時注意。
                    .units(Unit.METRIC)
                    .language("en")
                    .await();
        }
        catch (ApiException e)
        {
            Log.e(debug, "An error occurs while trying to get the result:" + e.getMessage());
        }
        catch (InterruptedException e)
        {
            Log.e(debug, "An error occurs while trying to get the result:" + e.getMessage());
        }
        catch (IOException e)
        {
            Log.e(debug, "An error occurs while trying to get the result:" + e.getMessage());
        }
        mResult = result;

        //stepPresent = new LatLng(result.routes[0].legs[0].startLocation.lat, result.routes[0].legs[0].startLocation.lng);
        return result;
    }

    DirectionsResult getmResult()
    {
        return mResult;
    }


    private double getDistance(com.google.maps.model.LatLng a, LatLng b)    // 直接用座標計算距離，由於有將地球曲面列入計算條件，結果非常精準(這當然是我去抄來的)
    {
        double x,y,out;
        double PI=3.14159265;
        double R=6.371229*1e6;
        x=(b.longitude-a.lng)*PI*R*Math.cos( ((b.latitude+a.lat)/2) *PI/180)/180;
        y=(b.latitude-a.lat)*PI*R/180;
        out=Math.hypot(x,y);
        return out;
    }

    void setLocationChanged(LatLng newLocation)    // 座標更新時，系統會呼叫這個方法，其實就是座標的set方法
    {
        mLocation = new LatLng(newLocation.latitude, newLocation.longitude);
        locationChanged = true;
    }


    public void puase()    // 尚未有實際用處的方法
    {
        paused = true;
        Log.d(debug, "Guide paused");
    }

    void cancel()    // 導航工作被迫中止時需要呼叫此方法
    {
        if(guiding != null)
        {
            canceled = true;
            guiding = null;
        }
    }

    public void restart(LatLng newDestination)    // 尚未有實際用處的方法
    {
        desLocation = newDestination;
        getResult();
    }

    private void mResume()    // 尚未有實際用處的方法
    {
        paused = false;
        Message msg = mHandler.obtainMessage(Constants.NEW_RESULT);
        msg.sendToTarget();
    }


    /**
     *  專門處理導航指令解碼的類別。
     *  因原始的html_instructions無法直接使用，需經過加工
     *  而Arduino端的字串處理不如Java方便，
     *  可以利用此類別將原始指令轉換成較易讀的字串，再傳給Arduino
     */
    private class InstructionDecode    // 輸入DirectionsResult中的html_instruction，擷取出我們所需的資訊並回傳
    {
        private String[] directions = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};

        private String[] splitedInstruction(String instr)
        {
            /*
             * 由於html_instruction會將資訊以<b></b>兩標籤夾住，
             * 因此先將標籤替換成&或/這種單一符號，
             * 方便接下來的字串切割(字串切割的函式只能識別單字元)。
             */
            String tmp = instr
                    .replaceAll("</b>/<b>", "/")
                    .replaceAll(" <b>", "&")
                    .replaceAll("</b>", "&")
                    .replaceAll("<div style=\"font-size:0.9em\">", "")
                    .replaceAll("</div>", "&");
            return tmp.split("&");
        }


        String Classified(String instr)
        {
            String[] str = splitedInstruction(instr);
            List<String> directionList = Arrays.asList(directions);
            StringBuilder sb = new StringBuilder();
            try {
                if(str[0].contains("Head"))
                {
                    String tmp;
                    sb.append("0+").append(directionList.indexOf(str[1]));
                    if(str.length>3)
                    {
                        tmp = translateText(str[3]);
                        sb.append("+").append(tmp);
                        if (str.length > 5)
                        {
                                tmp = translateText(str[5]);
                                sb.append(" Head ").append(tmp);
                        }
                    }
                    else {
                        sb.append("+ ");
                    }
                }
                else if(str[0].contains("Continue onto"))
                {
                    String tmp;
                    tmp = translateText(str[1]);
                    sb.append("1+").append(tmp);
                    if(str.length>2)
                    {
                        sb.append("Keep going forward");
                    }
                }
                else
                {
                    if(str[0].contains("Turn"))
                        sb.append("2+");
                    else
                        sb.append("3+");

                    if(str[1].contains("left"))
                        sb.append("0+");
                    else
                        sb.append("1+");

                    if(str[2].contains("onto"))
                        sb.append("0+");
                    else
                        sb.append("1+");

                    String tmp;
                        tmp = translateText(str[3]);
                    sb.append(tmp);

                    if(str.length>4)
                    {
                            sb.append("Keep going forward");
                    }
                }
            }
            catch(ArrayIndexOutOfBoundsException e)
            {
                Log.e(debug, "ArrayIndexOutOfBoundsException: "+instr);
                sb.append("+-1");
                return sb.toString();
            }
            return sb.toString();
        }

        private String translateText(String sourceText)
        {
            Translate translate = createTranslateService();
            Translation translation = translate.translate(sourceText,
                    Translate.TranslateOption.targetLanguage("en"),
                    Translate.TranslateOption.sourceLanguage("zh-TW"));
            return translation.getTranslatedText();
        }

        private Translate createTranslateService()
        {
            return TranslateOptions.newBuilder().setApiKey(key).build().getService();
        }
    }





    ///////////////////////////////////////maps' operations//////////////////////////////

    private GeoApiContext getGeoApiContext()
    {
        GeoApiContext.Builder builder = new GeoApiContext.Builder()
                .apiKey(key)
                .queryRateLimit(3)
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .writeTimeout(1, TimeUnit.SECONDS)
                .disableRetries();
        return builder.build();
    }

}
