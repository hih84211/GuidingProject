package com.p1.genius.bluetoothconectionapp;

import android.app.Application;

public class Global extends Application
{
    public String BTdeviceAddr = null;
    private Boolean tryBluetoothReconnect = false;

    public synchronized void setDeviceAddr(String addr)
    {
        BTdeviceAddr = addr;
    }
    public synchronized String getDeviceAddr()
    {
        return BTdeviceAddr;
    }
    public synchronized void setConnectState(Boolean newState)
    {
        tryBluetoothReconnect = newState;
    }
    public synchronized Boolean getConncetState()
    {
        return tryBluetoothReconnect;
    }
}
