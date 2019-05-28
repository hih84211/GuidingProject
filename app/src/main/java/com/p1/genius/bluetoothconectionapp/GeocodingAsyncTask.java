package com.p1.genius.bluetoothconectionapp;

import android.os.AsyncTask;
import android.util.Log;
import com.google.type.LatLng;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

import org.json.JSONException;
import org.json.JSONObject;

class DirRequestTask extends AsyncTask<Void, Void, Void>
{
    private String key = "AIzaSyAHG_MzhRM-5IsfnKDi-cqKT7VS4ssv64Q";
    @Override
    protected Void doInBackground(Void... params)
    {
        String urlrqst = getRequestUrl(params[0].toString());
        StringBuilder sb = new StringBuilder();
        JSONObject mJSON;

        try
        {
            URL url = new URL(urlrqst);
            BufferedReader read = new BufferedReader(new InputStreamReader
                    (url.openStream()));
            String line = read.readLine();
            while(line != null)
            {
                Log.d("direction JSON:", line);
                sb.append(line);
                line = read.readLine();
            }

            try
            {
                mJSON = new JSONObject(sb.toString());
            }
            catch (JSONException e)
            {
                Log.e("JSON Error: ", e.getLocalizedMessage());
            }
        }
        catch (MalformedURLException e)
        {
            e.printStackTrace();
            Log.e("Direction Error",e.getMessage());Log.e("Direction Error",e.getMessage());Log.e("Direction Error",e.getMessage());
        }
        catch (IOException e)
        {
            Log.e("Direction Error",e.getMessage());Log.e("Direction Error",e.getMessage());Log.e("Direction Error",e.getMessage());
        }

        return null;
    }

    private String getRequestUrl(String addr)
    {
        String address = "address=" + addr;
        String param = address + "&key=" + key;
        return "https://maps.googleapis.com/maps/api/geocode/json?" + param;
    }
}
