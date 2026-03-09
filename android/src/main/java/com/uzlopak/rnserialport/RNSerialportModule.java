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
  enum Error {
    CONNECT_BAUDRATE_INVALID ("CONNECT_BAUDRATE_INVALID", "BaudRate is invalid."),
    CONNECT_DEVICE_NAME_INVALID ("CONNECT_DEVICE_NAME_INVALID", "Device name cannot be invalid or empty."),
    CONNECTION_FAILED ("CONNECTION_FAILED", "Connection Failed."),
    COULD_NOT_OPEN_SERIALPORT ("COULD_NOT_OPEN_SERIALPORT", "Could not open Serial Port."),
    COULD_NOT_READ_DATA ("COULD_NOT_READ_DATA", "Error reading from port."),
    DEVICE_NOT_FOUND ("DEVICE_NOT_FOUND", "Device not found."),
    DEVICE_NOT_SUPPORTED ("DEVICE_NOT_SUPPORTED", "Device not supported."),
    DRIVER_TYPE_NOT_FOUND ("DRIVER_TYPE_NOT_FOUND", "Driver type is not defined."),
    NO_CONNECTION ("NO_CONNECTION", "There is no connection."),
    SERIALPORT_ALREADY_CONNECTED ("SERIALPORT_ALREADY_CONNECTED", "Serial Port is already connected."),
    SERIALPORT_ALREADY_DISCONNECTED ("SERIALPORT_ALREADY_DISCONNECTED", "Serial Port is already disconnected."),
    SERVICE_STOP_FAILED ("SERVICE_STOP_FAILED", "Service could not be stopped. Please first close open connections."),
    USB_SERVICE_NOT_STARTED ("USB_SERVICE_NOT_STARTED", "Usb service not started. Please first start Usb service."),
    USER_DID_NOT_ALLOW_TO_CONNECT ("USER_DID_NOT_ALLOW_TO_CONNECT", "User did not allow to connect.");

    final String code;
    final String message;

    Error(String code, String message) { 
      this.code = code;
      this.message = message; 
    }
  }

  private final String ACTION_USB_ATTACHED                     = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
  private final String ACTION_USB_DETACHED                     = "android.hardware.usb.action.USB_DEVICE_DETACHED";

  private final String ACTION_USB_CONNECTED                    = "com.uzlopak.rnserialport.USB_CONNECTED";
  private final String ACTION_USB_DISCONNECTED                 = "com.uzlopak.rnserialport.USB_DISCONNECTED";
  private final String ACTION_USB_NOT_OPENED                   = "com.uzlopak.rnserialport.USB_NOT_OPENED";
  private final String ACTION_USB_NOT_SUPPORTED                = "com.uzlopak.rnserialport.USB_NOT_SUPPORTED";
  private final String ACTION_USB_PERMISSION                   = "com.uzlopak.rnserialport.USB_PERMISSION";
  private final String ACTION_USB_PERMISSION_GRANTED           = "com.uzlopak.rnserialport.USB_PERMISSION_GRANTED";
  private final String ACTION_USB_PERMISSION_NOT_GRANTED       = "com.uzlopak.rnserialport.USB_PERMISSION_NOT_GRANTED";

  private final String ON_ERROR_EVENT               = "onError";
  private final String ON_CONNECTED_EVENT           = "onConnected";
  private final String ON_DISCONNECTED_EVENT        = "onDisconnected";
  private final String ON_DEVICE_ATTACHED_EVENT     = "onDeviceAttached";
  private final String ON_DEVICE_DETACHED_EVENT     = "onDeviceDetached";
  private final String ON_SERVICE_STARTED           = "onServiceStarted";
  private final String ON_SERVICE_STOPPED           = "onServiceStopped";
  private final String ON_READ_DATA_FROM_PORT       = "onReadDataFromPort";
  private final String ON_USB_PERMISSION_GRANTED    = "onUsbPermissionGranted";

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
  private int PARITY       = UsbSerialInterface.PARITY_NONE;
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
        case ACTION_USB_ATTACHED: emitEvent(ON_DEVICE_ATTACHED_EVENT, null); checkAutoConnect(); break;
        case ACTION_USB_CONNECTED: emitEvent(ON_CONNECTED_EVENT, null); break;
        case ACTION_USB_DETACHED: emitEvent(ON_DEVICE_DETACHED_EVENT, null); stopConnection(); break;
        case ACTION_USB_DISCONNECTED: emitEvent(ON_DISCONNECTED_EVENT, null); break;
        case ACTION_USB_NOT_OPENED: emitErrorEvent(Error.COULD_NOT_OPEN_SERIALPORT); break;
        case ACTION_USB_NOT_SUPPORTED: emitErrorEvent(Error.DEVICE_NOT_SUPPORTED); break;
        case ACTION_USB_PERMISSION : startConnection(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)); break;
        case ACTION_USB_PERMISSION_GRANTED: emitEvent(ON_USB_PERMISSION_GRANTED, null); break;
        case ACTION_USB_PERMISSION_NOT_GRANTED: emitErrorEvent(Error.USER_DID_NOT_ALLOW_TO_CONNECT); break;
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

  private void emitErrorEvent(Error error) {
    this.emitErrorEvent(error, null, null);
  }
  private void emitErrorEvent(Error error, String key, String value) {
    WritableMap err = Arguments.createMap();
    err.putString("code", error.code);
    err.putString("message", error.message);
    if (key != null && value != null)
      err.putString(key, value);

    emitEvent(ON_ERROR_EVENT, err);
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
    PARITY       = UsbSerialInterface.PARITY_NONE;
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
      emitErrorEvent(Error.DRIVER_TYPE_NOT_FOUND);
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
  
    IntentFilter filter = new IntentFilter();
    filter.addAction(ACTION_USB_ATTACHED);
    filter.addAction(ACTION_USB_CONNECTED);
    filter.addAction(ACTION_USB_DETACHED);
    filter.addAction(ACTION_USB_DISCONNECTED);
    filter.addAction(ACTION_USB_NOT_OPENED);
    filter.addAction(ACTION_USB_NOT_SUPPORTED);
    filter.addAction(ACTION_USB_PERMISSION_GRANTED);
    filter.addAction(ACTION_USB_PERMISSION_NOT_GRANTED);
    filter.addAction(ACTION_USB_PERMISSION);
  
    reactContext.registerReceiver(mUsbReceiver, filter);

    usbManager = (UsbManager) reactContext.getSystemService(Context.USB_SERVICE);

    usbServiceStarted = true;

    emitEvent(ON_SERVICE_STARTED, null);

    emitEvent(ON_DEVICE_ATTACHED_EVENT, null);

    checkAutoConnect();
  }

  @ReactMethod
  public void stopUsbService() {
    if(serialPortConnected) {
      emitErrorEvent(Error.SERVICE_STOP_FAILED);
      return;
    }
    if(!usbServiceStarted) {
      return;
    }
    reactContext.unregisterReceiver(mUsbReceiver);
    usbServiceStarted = false;
    emitEvent(ON_SERVICE_STOPPED, null);
  }

  @ReactMethod
  public void getDeviceList(Promise promise) {
    if(!usbServiceStarted) {
      promise.reject(Error.USB_SERVICE_NOT_STARTED.code, Error.USB_SERVICE_NOT_STARTED.message);
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
        emitErrorEvent(Error.USB_SERVICE_NOT_STARTED);
        return;
      }

      if(serialPortConnected) {
        emitErrorEvent(Error.SERIALPORT_ALREADY_CONNECTED);
        return;
      }

      if(deviceName == null || deviceName.trim().isEmpty()) {
        emitErrorEvent(Error.CONNECT_DEVICE_NAME_INVALID, "deviceName", deviceName);
        return;
      }

      if(baudRate < 1){
        emitErrorEvent(Error.CONNECT_BAUDRATE_INVALID, "baudRate", String.valueOf(baudRate));
        return;
      }

      if(!autoConnect) {
        this.BAUD_RATE = baudRate;
      }

      UsbDevice d = chooseDevice(deviceName);

      if(d == null) {
        emitErrorEvent(Error.DEVICE_NOT_FOUND, "deviceName", deviceName);
        return;
      }

      this.device = d;

      requestUsbPermission(this.device);

    } catch (Exception err) {
      emitErrorEvent(Error.CONNECTION_FAILED, "exceptionMessage", err.getMessage());
    }
  }

  @ReactMethod
  public void disconnect() {
    if(!usbServiceStarted){
      emitErrorEvent(Error.USB_SERVICE_NOT_STARTED);
      return;
    }

    if(!serialPortConnected) {
      emitErrorEvent(Error.SERIALPORT_ALREADY_DISCONNECTED);
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
      promise.reject(Error.DEVICE_NOT_FOUND.code, Error.DEVICE_NOT_FOUND.message);
    } else {
      promise.resolve(UsbSerialDevice.isSupported(d));
    }
  }

  @ReactMethod
  public void write(ReadableArray bytes) {
    if(!usbServiceStarted){
      emitErrorEvent(Error.USB_SERVICE_NOT_STARTED);
      return;
    }
    if(!serialPortConnected || serialPort == null) {
      emitErrorEvent(Error.NO_CONNECTION);
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

        reactContext.sendBroadcast(new Intent(ACTION_USB_CONNECTED));
      } catch (Exception error) {
        emitErrorEvent(Error.CONNECTION_FAILED, "exceptionMessage", error.getMessage());
      }
    }
  }

  private void requestUsbPermission(UsbDevice device) {
    if(device == null)
      return;
    usbManager.requestPermission(device, PendingIntent.getBroadcast(reactContext, 0 , new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE));
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
      emitErrorEvent(Error.COULD_NOT_OPEN_SERIALPORT);
      return;
    }

    new ConnectionThread().start();
  }

  private void stopConnection() {
    if (serialPortConnected) {
      serialPort.close();
      reactContext.sendBroadcast(new Intent(ACTION_USB_DISCONNECTED));
    }

    connection = null;
    device = null;
    serialPortConnected = false;
    serialPort = null;
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
        emitErrorEvent(Error.COULD_NOT_READ_DATA, "exceptionMessage", err.getMessage());
      }
    }
  };
}