package com.uzlopak.rnserialport;

import android.app.PendingIntent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.WritableNativeArray;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.primitives.UnsignedBytes;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

public class RNSerialportModule extends ReactContextBaseJavaModule {
  ////////////////////////// Errors //////////////////////////

  private final int ERROR_DEVICE_NOT_FOUND                = 1;
  private final int ERROR_CONNECT_DEVICE_NAME_INVALID     = 2;
  private final int ERROR_CONNECT_BAUDRATE_EMPTY          = 3;
  private final int ERROR_CONNECTION_FAILED               = 4;
  private final int ERROR_COULD_NOT_OPEN_SERIALPORT       = 5;
  private final int ERROR_SERIALPORT_ALREADY_CONNECTED    = 6;
  private final int ERROR_SERIALPORT_ALREADY_DISCONNECTED = 7;
  private final int ERROR_USB_SERVICE_NOT_STARTED         = 8;
  private final int ERROR_USER_DID_NOT_ALLOW_TO_CONNECT   = 9;
  private final int ERROR_SERVICE_STOP_FAILED             = 10;
  private final int ERROR_THERE_IS_NO_CONNECTION          = 11;
  private final int ERROR_NOT_READED_DATA                 = 12;
  private final int ERROR_DRIVER_TYPE_NOT_FOUND           = 13;
  private final int ERROR_DEVICE_NOT_SUPPORTED            = 14;

  private final String ERROR_DEVICE_NOT_FOUND_MESSAGE                   = "Device not found!";
  private final String ERROR_CONNECT_DEVICE_NAME_INVALID_MESSAGE        = "Device name cannot be invalid or empty!";
  private final String ERROR_CONNECT_BAUDRATE_EMPTY_MESSAGE             = "BaudRate cannot be invalid!";
  private final String ERROR_CONNECTION_FAILED_MESSAGE                  = "Connection Failed!";
  private final String ERROR_COULD_NOT_OPEN_SERIALPORT_MESSAGE          = "Could not open Serial Port!";
  private final String ERROR_SERIALPORT_ALREADY_CONNECTED_MESSAGE       = "Serial Port is already connected";
  private final String ERROR_SERIALPORT_ALREADY_DISCONNECTED_MESSAGE    = "Serial Port is already disconnected";
  private final String ERROR_USB_SERVICE_NOT_STARTED_MESSAGE            = "Usb service not started. Please first start Usb service!";
  private final String ERROR_USER_DID_NOT_ALLOW_TO_CONNECT_MESSAGE      = "User did not allow to connect";
  private final String ERROR_SERVICE_STOP_FAILED_MESSAGE                = "Service could not stopped. Please first close connection";
  private final String ERROR_THERE_IS_NO_CONNECTION_MESSAGE             = "There is no connection";
  private final String ERROR_NOT_READED_DATA_MESSAGE                    = "Error reading from port";
  private final String ERROR_DRIVER_TYPE_NOT_FOUND_MESSAGE              = "Driver type is not defined";
  private final String ERROR_DEVICE_NOT_SUPPORTED_MESSAGE               = "Device not supported";

  private final String ACTION_USB_READY                        = "com.felhr.connectivityservices.USB_READY";
  private final String ACTION_USB_ATTACHED                     = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
  private final String ACTION_USB_DETACHED                     = "android.hardware.usb.action.USB_DEVICE_DETACHED";
  private final String ACTION_USB_NOT_SUPPORTED                = "com.felhr.usbservice.USB_NOT_SUPPORTED";
  private final String ACTION_NO_USB                           = "com.felhr.usbservice.NO_USB";
  private final String ACTION_USB_PERMISSION_GRANTED           = "com.felhr.usbservice.USB_PERMISSION_GRANTED";
  private final String ACTION_USB_PERMISSION_NOT_GRANTED       = "com.felhr.usbservice.USB_PERMISSION_NOT_GRANTED";
  private final String ACTION_USB_DISCONNECTED                 = "com.felhr.usbservice.USB_DISCONNECTED";
  private final String ACTION_USB_PERMISSION                   = "com.android.example.USB_PERMISSION";
  private final String ACTION_USB_NOT_OPENED                   = "com.uzlopak.rnserialport.USB_NOT_OPENED";
  private final String ACTION_USB_CONNECT                      = "com.uzlopak.rnserialport.USB_CONNECT";

  //react-native events
  private final String ON_ERROR_EVENT               = "onError";
  private final String ON_CONNECTED_EVENT           = "onConnected";
  private final String ON_DISCONNECTED_EVENT        = "onDisconnected";
  private final String ON_DEVICE_ATTACHED_EVENT     = "onDeviceAttached";
  private final String ON_DEVICE_DETACHED_EVENT     = "onDeviceDetached";
  private final String ON_SERVICE_STARTED           = "onServiceStarted";
  private final String ON_SERVICE_STOPPED           = "onServiceStopped";
  private final String ON_READ_DATA_FROM_PORT       = "onReadDataFromPort";
  private final String ON_USB_PERMISSIONS_GRANTED   = "onUsbPermissionGranted";

  private final ReactApplicationContext reactContext;
  public RNSerialportModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
  }

  @Override
  public String getName() {
    return "RNSerialport";
  }

  //SUPPORTED DRIVER LIST

  private final List<String> driverList = new ArrayList<String>(Arrays.asList("AUTO", "ftdi", "cp210x", "pl2303", "ch34x", "cdc"));

  private UsbManager usbManager;
  private UsbDevice device;
  private UsbDeviceConnection connection;
  private UsbSerialDevice serialPort;
  private boolean serialPortConnected;

  //Connection Settings
  private int DATA_BIT     = UsbSerialInterface.DATA_BITS_8;
  private int STOP_BIT     = UsbSerialInterface.STOP_BITS_1;
  private int PARITY       =  UsbSerialInterface.PARITY_NONE;
  private int FLOW_CONTROL = UsbSerialInterface.FLOW_CONTROL_OFF;
  private int BAUD_RATE = 9600;

  private boolean autoConnect = false;
  private int autoConnectBaudRate = 9600;
  private int portInterface = -1;
  private String driver = "AUTO";

  private boolean usbServiceStarted = false;

  private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context ctx, Intent intent) {
      switch (intent.getAction()) {
        case ACTION_USB_CONNECT: emitEvent(ON_CONNECTED_EVENT, null); break;
        case ACTION_USB_DISCONNECTED: emitEvent(ON_DISCONNECTED_EVENT, null); break;
        case ACTION_USB_NOT_SUPPORTED: emitErrorEvent(ERROR_DEVICE_NOT_SUPPORTED, ERROR_DEVICE_NOT_SUPPORTED_MESSAGE); break;
        case ACTION_USB_NOT_OPENED: emitErrorEvent(ERROR_COULD_NOT_OPEN_SERIALPORT, ERROR_COULD_NOT_OPEN_SERIALPORT_MESSAGE); break;
        case ACTION_USB_ATTACHED: emitEvent(ON_DEVICE_ATTACHED_EVENT, null); handleUsbAttached(); break;
        case ACTION_USB_DETACHED: emitEvent(ON_DEVICE_DETACHED_EVENT, null); handleUsbDetached(); break;
        case ACTION_USB_PERMISSION : startConnection(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)); break;
        case ACTION_USB_PERMISSION_GRANTED: emitEvent(ON_USB_PERMISSIONS_GRANTED, null); break;
        case ACTION_USB_PERMISSION_NOT_GRANTED: emitErrorEvent(ERROR_USER_DID_NOT_ALLOW_TO_CONNECT, ERROR_USER_DID_NOT_ALLOW_TO_CONNECT_MESSAGE); break;
      }
    }
  };

  private void emitEvent(String eventName, Object data) {
    try {
      if(reactContext.hasActiveCatalystInstance()) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, data);
      }
    }
    catch (Exception error) {}
  }

  private void emitErrorEvent(int code, String message) {
    emitEvent(ON_ERROR_EVENT, createError(code, message));
  }

  private WritableMap createError(int code, String message) {
    WritableMap err = Arguments.createMap();
    err.putBoolean("status", false);
    err.putInt("errorCode", code);
    err.putString("errorMessage", message);

    return err;
  }

  private void setFilters() {
    IntentFilter filter = new IntentFilter();
    filter.addAction(ACTION_USB_PERMISSION_GRANTED);
    filter.addAction(ACTION_NO_USB);
    filter.addAction(ACTION_USB_CONNECT);
    filter.addAction(ACTION_USB_DISCONNECTED);
    filter.addAction(ACTION_USB_NOT_SUPPORTED);
    filter.addAction(ACTION_USB_PERMISSION_NOT_GRANTED);
    filter.addAction(ACTION_USB_PERMISSION);
    filter.addAction(ACTION_USB_ATTACHED);
    filter.addAction(ACTION_USB_DETACHED);
    reactContext.registerReceiver(mUsbReceiver, filter);
  }

  /******************************* BEGIN PUBLIC SETTER METHODS **********************************/

  @ReactMethod
  public void setDataBit(int DATA_BIT) {
    this.DATA_BIT = DATA_BIT;
  }
  @ReactMethod
  public void setStopBit(int STOP_BIT) {
    this.STOP_BIT = STOP_BIT;
  }
  @ReactMethod
  public void setParity(int PARITY) {
    this.PARITY = PARITY;
  }
  @ReactMethod
  public void setFlowControl(int FLOW_CONTROL) {
    this.FLOW_CONTROL = FLOW_CONTROL;
  }

  @ReactMethod
  public void loadDefaultConnectionSetting() {
    DATA_BIT     = UsbSerialInterface.DATA_BITS_8;
    STOP_BIT     = UsbSerialInterface.STOP_BITS_1;
    PARITY       =  UsbSerialInterface.PARITY_NONE;
    FLOW_CONTROL = UsbSerialInterface.FLOW_CONTROL_OFF;
  }
  @ReactMethod
  public void setAutoConnect(boolean autoConnect) {
    this.autoConnect = autoConnect;
  }
  @ReactMethod
  public void setAutoConnectBaudRate(int baudRate) {
    this.autoConnectBaudRate = baudRate;
  }
  @ReactMethod
  public void setInterface(int iFace) {
    this.portInterface = iFace;
  }

  @ReactMethod
  public void setDriver(String driver) {
    if(driver == null || driver.isEmpty() || !driverList.contains(driver.trim())) {
      emitErrorEvent(ERROR_DRIVER_TYPE_NOT_FOUND, ERROR_DRIVER_TYPE_NOT_FOUND_MESSAGE);
      return;
    }

    this.driver = driver.trim();
  }

  /********************************************* END **********************************************/

  @ReactMethod
  public void startUsbService() {
    if(usbServiceStarted) {
      return;
    }
  
    setFilters();

    usbManager = (UsbManager) reactContext.getSystemService(Context.USB_SERVICE);

    usbServiceStarted = true;

    emitEvent(ON_SERVICE_STARTED, null);

    emitEvent(ON_DEVICE_ATTACHED_EVENT, null);

    checkAutoConnect();
  }

  @ReactMethod
  public void stopUsbService() {
    if(serialPortConnected) {
      emitErrorEvent(ERROR_SERVICE_STOP_FAILED, ERROR_SERVICE_STOP_FAILED_MESSAGE);
      return;
    }
    if(!usbServiceStarted) {
      return;
    }
    reactContext.unregisterReceiver(mUsbReceiver);
    usbServiceStarted = false;
    emitEvent(ON_SERVICE_STOPPED, null);
  }

  private void handleUsbAttached() {
    if (autoConnect) {
      UsbDevice d = chooseFirstDevice();
      if (d != null) {
        connectDevice(d.getDeviceName(), autoConnectBaudRate);
      }
    }
  }

  private void handleUsbDetached() {
    if (serialPortConnected) {
      stopConnection();
    }
  }

  @ReactMethod
  public void getDeviceList(Promise promise) {
    if(!usbServiceStarted) {
      promise.reject(String.valueOf(ERROR_USB_SERVICE_NOT_STARTED), ERROR_USB_SERVICE_NOT_STARTED_MESSAGE);
      return;
    }
    
    HashMap<String, UsbDevice> devices = usbManager.getDeviceList();

    WritableArray deviceList = Arguments.createArray();

    if(!devices.isEmpty()) {
      for(Map.Entry<String, UsbDevice> entry: devices.entrySet()) {
        deviceList.pushMap(mapDevice(entry.getValue()));
      }
    }

    promise.resolve(deviceList);
  }

  private WritableMap mapDevice(UsbDevice usbDevice) {
    WritableMap map = Arguments.createMap();
    map.putInt("deviceId", usbDevice.getDeviceId());
    map.putString("deviceName", usbDevice.getDeviceName());
    map.putInt("vendorId", usbDevice.getVendorId());
    map.putInt("productId", usbDevice.getProductId());
    return map;
  }

  @ReactMethod
  public void connectDevice(String deviceName, int baudRate) {
    try {
      if(!usbServiceStarted){
        emitErrorEvent(ERROR_USB_SERVICE_NOT_STARTED, ERROR_USB_SERVICE_NOT_STARTED_MESSAGE);
        return;
      }

      if(serialPortConnected) {
        emitErrorEvent(ERROR_SERIALPORT_ALREADY_CONNECTED, ERROR_SERIALPORT_ALREADY_CONNECTED_MESSAGE);
        return;
      }

      if(deviceName == null || deviceName.trim().isEmpty()) {
        emitErrorEvent(ERROR_CONNECT_DEVICE_NAME_INVALID, ERROR_CONNECT_DEVICE_NAME_INVALID_MESSAGE);
        return;
      }

      if(baudRate < 1){
        emitErrorEvent(ERROR_CONNECT_BAUDRATE_EMPTY, ERROR_CONNECT_BAUDRATE_EMPTY_MESSAGE);
        return;
      }

      if(!autoConnect) {
        this.BAUD_RATE = baudRate;
      }

      UsbDevice d = chooseDevice(deviceName);

      if(d == null) {
        emitErrorEvent(ERROR_DEVICE_NOT_FOUND, ERROR_DEVICE_NOT_FOUND_MESSAGE + deviceName);
        return;
      }

      this.device = d;

      requestUserPermission();

    } catch (Exception err) {
      emitErrorEvent(ERROR_CONNECTION_FAILED, ERROR_CONNECTION_FAILED_MESSAGE + " Catch Error Message:" + err.getMessage());
    }
  }

  @ReactMethod
  public void disconnect() {
    if(!usbServiceStarted){
      emitErrorEvent(ERROR_USB_SERVICE_NOT_STARTED, ERROR_USB_SERVICE_NOT_STARTED_MESSAGE);
      return;
    }

    if(!serialPortConnected) {
      emitErrorEvent(ERROR_SERIALPORT_ALREADY_DISCONNECTED, ERROR_SERIALPORT_ALREADY_DISCONNECTED_MESSAGE);
      return;
    }
    stopConnection();
  }

  @ReactMethod
  public void isOpen(Promise promise) {
    promise.resolve(serialPortConnected);
  }

 @ReactMethod
 public void isServiceStarted(Promise promise) {
    promise.resolve(usbServiceStarted);
 }

  @ReactMethod
  public void isSupported(String deviceName, Promise promise) {
    UsbDevice d = chooseDevice(deviceName);

    if(d == null) {
      promise.reject(String.valueOf(ERROR_DEVICE_NOT_FOUND), ERROR_DEVICE_NOT_FOUND_MESSAGE);
    } else {
      promise.resolve(UsbSerialDevice.isSupported(d));
    }
  }

  @ReactMethod
  public void write(ReadableArray bytes) {
    if(!usbServiceStarted){
      emitErrorEvent(ERROR_USB_SERVICE_NOT_STARTED, ERROR_USB_SERVICE_NOT_STARTED_MESSAGE);
      return;
    }
    if(!serialPortConnected || serialPort == null) {
      emitErrorEvent(ERROR_THERE_IS_NO_CONNECTION, ERROR_THERE_IS_NO_CONNECTION_MESSAGE);
      return;
    }

    serialPort.write(toByteArray(bytes));
  }

  private byte[] toByteArray(ReadableArray bytes) {
    byte[] buffer = new byte[bytes.size()];
    for (int i = 0; i < bytes.size(); i++) {
      buffer[i] = (byte) bytes.getInt(i);
    }
    return buffer;
  }

  private UsbDevice chooseDevice(String deviceName) {
    HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
    if(usbDevices.isEmpty()) {
      return null;
    }

    for (Map.Entry<String, UsbDevice> entry: usbDevices.entrySet()) {
      UsbDevice d = entry.getValue();

      if(d.getDeviceName().equals(deviceName)) {
        return d;
      }
    }

    return null;
  }

  private UsbDevice chooseFirstDevice() {
    HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
    if(usbDevices.isEmpty()) {
      return null;
    }

    for (Map.Entry<String, UsbDevice> entry: usbDevices.entrySet()) {
      UsbDevice d = entry.getValue();

      if ((d.getVendorId() == 0x1d6b && d.getProductId() <= 0x0003) || (d.getVendorId() == 0x5c6 && d.getProductId() == 0x904c)) {
        continue;
      }

      return d;
    }
    return null;
  }

  private void checkAutoConnect() {
    if(!autoConnect || serialPortConnected)
      return;

    UsbDevice d = chooseFirstDevice();
    if(d != null) {
      connectDevice(d.getDeviceName(), autoConnectBaudRate);
    }
  }
  private class ConnectionThread extends Thread {
    @Override
    public void run() {
      try {
        serialPort = driver.equals("AUTO")
          ? UsbSerialDevice.createUsbSerialDevice(device, connection, portInterface)
          : UsbSerialDevice.createUsbSerialDevice(driver, device, connection, portInterface);

        if(serialPort == null) {
          // No driver for given device
          reactContext.sendBroadcast(new Intent(ACTION_USB_NOT_SUPPORTED));
          return;
        }

        if(!serialPort.open()){
          reactContext.sendBroadcast(new Intent(ACTION_USB_NOT_OPENED));
          return;
        }

        serialPortConnected = true;
        serialPort.setBaudRate(autoConnect ? autoConnectBaudRate : BAUD_RATE);
        serialPort.setDataBits(DATA_BIT);
        serialPort.setStopBits(STOP_BIT);
        serialPort.setParity(PARITY);
        serialPort.setFlowControl(FLOW_CONTROL);
        serialPort.read(mCallback);

        reactContext.sendBroadcast(new Intent(ACTION_USB_READY));
        reactContext.sendBroadcast(new Intent(ACTION_USB_CONNECT));
      } catch (Exception error) {
        emitErrorEvent(ERROR_CONNECTION_FAILED, ERROR_CONNECTION_FAILED_MESSAGE + " exceptionErrorMessage: " + error.getMessage());
      }
    }
  }

  private void requestUserPermission() {
    if(device == null)
      return;
    PendingIntent mPendingIntent = PendingIntent.getBroadcast(reactContext, 0 , new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
    usbManager.requestPermission(device, mPendingIntent);
  }

  private void startConnection(boolean granted) {
    if (!granted) {
      connection = null;
      device = null;
      reactContext.sendBroadcast(new Intent(ACTION_USB_PERMISSION_NOT_GRANTED));
      return;
    }

    reactContext.sendBroadcast(new Intent(ACTION_USB_PERMISSION_GRANTED));
    connection = usbManager.openDevice(device);

    if (connection == null) {
      emitErrorEvent(ERROR_COULD_NOT_OPEN_SERIALPORT, ERROR_COULD_NOT_OPEN_SERIALPORT_MESSAGE);
      return;
    }

    new ConnectionThread().start();
  }

  private void stopConnection() {
    if (serialPortConnected) {
      serialPort.close();
      connection = null;
      device = null;
      reactContext.sendBroadcast(new Intent(ACTION_USB_DISCONNECTED));
      serialPortConnected = false;
      serialPort = null;
    } else {
      reactContext.sendBroadcast(new Intent(ACTION_USB_DETACHED));
    }
  }

  private final UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {
    @Override
    public void onReceivedData(byte[] bytes) {
      try {
        WritableArray uint8Array = new WritableNativeArray();
        for(byte b: bytes) {
          uint8Array.pushInt(UnsignedBytes.toInt(b));
        }

        WritableMap params = Arguments.createMap();
        params.putArray("payload", uint8Array);

        emitEvent(ON_READ_DATA_FROM_PORT, params);

      } catch (Exception err) {
        emitErrorEvent(ERROR_NOT_READED_DATA, ERROR_NOT_READED_DATA_MESSAGE + " System Message: " + err.getMessage());
      }
    }
  };
}