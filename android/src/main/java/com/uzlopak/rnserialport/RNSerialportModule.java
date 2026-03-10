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
import java.util.concurrent.ConcurrentHashMap;
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

  private final String ACTION_USB_ATTACHED               = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
  private final String ACTION_USB_DETACHED               = "android.hardware.usb.action.USB_DEVICE_DETACHED";

  private final String ACTION_USB_CONNECTED              = "com.uzlopak.rnserialport.USB_CONNECTED";
  private final String ACTION_USB_DISCONNECTED           = "com.uzlopak.rnserialport.USB_DISCONNECTED";
  private final String ACTION_USB_NOT_OPENED             = "com.uzlopak.rnserialport.USB_NOT_OPENED";
  private final String ACTION_USB_NOT_SUPPORTED          = "com.uzlopak.rnserialport.USB_NOT_SUPPORTED";
  private final String ACTION_USB_PERMISSION             = "com.uzlopak.rnserialport.USB_PERMISSION";
  private final String ACTION_USB_PERMISSION_GRANTED     = "com.uzlopak.rnserialport.USB_PERMISSION_GRANTED";
  private final String ACTION_USB_PERMISSION_NOT_GRANTED = "com.uzlopak.rnserialport.USB_PERMISSION_NOT_GRANTED";

  private final String EXTRA_USB_DEVICE_NAME             = "com.uzlopak.rnserialport.USB_DEVICE_NAME";

  private final String ON_ERROR_EVENT            = "onError";
  private final String ON_CONNECTED_EVENT        = "onConnected";
  private final String ON_DISCONNECTED_EVENT     = "onDisconnected";
  private final String ON_DEVICE_ATTACHED_EVENT  = "onDeviceAttached";
  private final String ON_DEVICE_DETACHED_EVENT  = "onDeviceDetached";
  private final String ON_SERVICE_STARTED        = "onServiceStarted";
  private final String ON_SERVICE_STOPPED        = "onServiceStopped";
  private final String ON_READ_DATA_FROM_PORT    = "onReadDataFromPort";
  private final String ON_USB_PERMISSION_GRANTED = "onUsbPermissionGranted";

  private final ReactApplicationContext reactContext;

  public RNSerialportModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
  }

  @Override
  public String getName() {
    return "RNSerialport";
  }

  private final List<String> driverList = new ArrayList<String>(Arrays.asList("AUTO", "ftdi", "cp210x", "pl2303", "ch34x", "cdc"));

  private final ConcurrentHashMap<String, UsbSerialDevice> serialPorts = new ConcurrentHashMap<String, UsbSerialDevice>();

  private UsbManager usbManager;

  private int DATA_BIT     = UsbSerialInterface.DATA_BITS_8;
  private int STOP_BIT     = UsbSerialInterface.STOP_BITS_1;
  private int PARITY       = UsbSerialInterface.PARITY_NONE;
  private int FLOW_CONTROL = UsbSerialInterface.FLOW_CONTROL_OFF;
  private int BAUD_RATE    = 9600;

  private boolean autoConnect     = false;
  private int autoConnectBaudRate = 9600;
  private int portInterface       = -1;
  private String driver           = "AUTO";

  private boolean usbServiceStarted = false;

  private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context ctx, Intent intent) {
      switch (intent.getAction()) {
        case ACTION_USB_ATTACHED: emitEvent(ON_DEVICE_ATTACHED_EVENT, null); checkAutoConnect(); break;
        case ACTION_USB_CONNECTED: emitEvent(ON_CONNECTED_EVENT, null); break;
        case ACTION_USB_DETACHED: {
          UsbDevice device = intent.getExtras().getParcelable(UsbManager.EXTRA_DEVICE);
          UsbSerialDevice serialPort = serialPorts.get(device.getDeviceName());
          emitEvent(ON_DEVICE_DETACHED_EVENT, null);
          stopConnection(serialPort);
          serialPorts.remove(device.getDeviceName());
          break;
        }
        case ACTION_USB_DISCONNECTED: emitEvent(ON_DISCONNECTED_EVENT, null); break;
        case ACTION_USB_NOT_OPENED: emitErrorEvent(Error.COULD_NOT_OPEN_SERIALPORT); break;
        case ACTION_USB_NOT_SUPPORTED: emitErrorEvent(Error.DEVICE_NOT_SUPPORTED); break;
        case ACTION_USB_PERMISSION: {
          boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
          UsbDevice device = intent.getExtras().getParcelable(UsbManager.EXTRA_DEVICE);

          if (!granted || device == null) {
            reactContext.sendBroadcast(new Intent(ACTION_USB_PERMISSION_NOT_GRANTED));
            return;
          }

          Intent permissionGrantedIntent = new Intent(ACTION_USB_PERMISSION_GRANTED);
          permissionGrantedIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
          reactContext.sendBroadcast(permissionGrantedIntent);
          break;
        }
        case ACTION_USB_PERMISSION_GRANTED: {
          UsbDevice device = intent.getExtras().getParcelable(UsbManager.EXTRA_DEVICE);
          emitEvent(ON_USB_PERMISSION_GRANTED, null);
          startConnection(device);
          break;
       }
        case ACTION_USB_PERMISSION_NOT_GRANTED: emitErrorEvent(Error.USER_DID_NOT_ALLOW_TO_CONNECT); break;
      }
    }
  };

  private void emitEvent(String eventName, Object data) {
    try {
      if (reactContext.hasActiveCatalystInstance()) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, data);
      }
    } catch (Exception error) {}
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
    if (driver == null || driver.isEmpty() || !driverList.contains(driver.trim())) {
      emitErrorEvent(Error.DRIVER_TYPE_NOT_FOUND);
      return;
    }
    this.driver = driver.trim();
  }

  @ReactMethod
  public void startUsbService() {
    if (this.usbServiceStarted) {
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

    this.usbServiceStarted = true;

    emitEvent(ON_SERVICE_STARTED, null);
    emitEvent(ON_DEVICE_ATTACHED_EVENT, null);

    checkAutoConnect();
  }

  @ReactMethod
  public void stopUsbService() {
    if (!serialPorts.isEmpty()) {
      emitErrorEvent(Error.SERVICE_STOP_FAILED);
      return;
    }
    if (!this.usbServiceStarted) {
      return;
    }
    reactContext.unregisterReceiver(mUsbReceiver);
    this.usbServiceStarted = false;
    emitEvent(ON_SERVICE_STOPPED, null);
  }

  @Override
  public void onCatalystInstanceDestroy() {
    super.onCatalystInstanceDestroy();
    if (this.usbServiceStarted) {
      reactContext.unregisterReceiver(mUsbReceiver);
      this.usbServiceStarted = false;
    }
    this.closeAllConnections();
  }

  @ReactMethod
  public void getDeviceList(Promise promise) {
    if (!this.usbServiceStarted) {
      promise.reject(Error.USB_SERVICE_NOT_STARTED.code, Error.USB_SERVICE_NOT_STARTED.message);
      return;
    }

    HashMap<String, UsbDevice> devices = this.usbManager.getDeviceList();
    WritableArray deviceList = Arguments.createArray();

    if (!devices.isEmpty()) {
      for (Map.Entry<String, UsbDevice> entry : devices.entrySet()) {
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
      if (!this.usbServiceStarted) {
        emitErrorEvent(Error.USB_SERVICE_NOT_STARTED);
        return;
      }

      if (deviceName == null || deviceName.trim().isEmpty()) {
        emitErrorEvent(Error.CONNECT_DEVICE_NAME_INVALID, "deviceName", deviceName);
        return;
      }

      if (serialPorts.get(deviceName) != null) {
        emitErrorEvent(Error.SERIALPORT_ALREADY_CONNECTED);
        return;
      }

      if (baudRate < 1) {
        emitErrorEvent(Error.CONNECT_BAUDRATE_INVALID, "baudRate", String.valueOf(baudRate));
        return;
      }

      if (!autoConnect) {
        this.BAUD_RATE = baudRate;
      }

      UsbDevice d = chooseDevice(deviceName);
      if (d == null) {
        emitErrorEvent(Error.DEVICE_NOT_FOUND, "deviceName", deviceName);
        return;
      }

      requestUsbPermission(d);

    } catch (Exception err) {
      emitErrorEvent(Error.CONNECTION_FAILED, "exceptionMessage", err.getMessage());
    }
  }

  @ReactMethod
  public void disconnect(String deviceName) {
    if (!this.usbServiceStarted) {
      emitErrorEvent(Error.USB_SERVICE_NOT_STARTED);
      return;
    }

    UsbSerialDevice serialPort = serialPorts.get(deviceName);
    if (serialPort == null) {
      emitErrorEvent(Error.SERIALPORT_ALREADY_DISCONNECTED);
      return;
    }

    stopConnection(serialPort);
    serialPorts.remove(deviceName);
  }

  private void closeAllConnections() {
    new ArrayList<>(serialPorts.values()).forEach(this::stopConnection);
    serialPorts.clear();
  }

  @ReactMethod
  public void disconnectAllDevices() {
    if (!this.usbServiceStarted) {
      emitErrorEvent(Error.USB_SERVICE_NOT_STARTED);
      return;
    }
    closeAllConnections();
  }

  @ReactMethod
  public void isOpen(String deviceName, Promise promise) {
    promise.resolve(serialPorts.containsKey(deviceName));
  }

  @ReactMethod
  public void isServiceStarted(Promise promise) {
    promise.resolve(this.usbServiceStarted);
  }

  @ReactMethod
  public void isSupported(String deviceName, Promise promise) {
    UsbDevice d = chooseDevice(deviceName);
    if (d == null) {
      promise.reject(Error.DEVICE_NOT_FOUND.code, Error.DEVICE_NOT_FOUND.message);
    } else {
      promise.resolve(UsbSerialDevice.isSupported(d));
    }
  }

  @ReactMethod
  public void write(String deviceName, ReadableArray bytes) {
    if (!this.usbServiceStarted) {
      emitErrorEvent(Error.USB_SERVICE_NOT_STARTED);
      return;
    }

    UsbSerialDevice serialPort = serialPorts.get(deviceName);
    if (serialPort == null) {
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
    HashMap<String, UsbDevice> usbDevices = this.usbManager.getDeviceList();
    if (usbDevices.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
      UsbDevice d = entry.getValue();
      if (d.getDeviceName().equals(deviceName)) {
        return d;
      }
    }
    return null;
  }

  private UsbDevice chooseFirstDevice() {
    HashMap<String, UsbDevice> usbDevices = this.usbManager.getDeviceList();
    if (usbDevices.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
      UsbDevice d = entry.getValue();
      if ((d.getVendorId() == 0x1d6b && d.getProductId() <= 0x0003) || (d.getVendorId() == 0x5c6 && d.getProductId() == 0x904c)) {
        continue;
      }
      return d;
    }
    return null;
  }

  private void checkAutoConnect() {
    if (!autoConnect || !serialPorts.isEmpty())
      return;
    UsbDevice d = chooseFirstDevice();
    if (d != null) {
      connectDevice(d.getDeviceName(), autoConnectBaudRate);
    }
  }

  private class ConnectionThread extends Thread {
    private final UsbDevice threadUsbDevice;
    private final UsbDeviceConnection threadUsbDeviceConnection;

    ConnectionThread(UsbDevice device, UsbDeviceConnection connection) {
      this.threadUsbDevice = device;
      this.threadUsbDeviceConnection = connection;
    }

    @Override
    public void run() {
      try {
        UsbSerialDevice serialPort = driver.equals("AUTO")
          ? UsbSerialDevice.createUsbSerialDevice(threadUsbDevice, threadUsbDeviceConnection, portInterface)
          : UsbSerialDevice.createUsbSerialDevice(driver, threadUsbDevice, threadUsbDeviceConnection, portInterface);

        if (serialPort == null) {
          reactContext.sendBroadcast(new Intent(ACTION_USB_NOT_SUPPORTED));
          return;
        }

        if (!serialPort.open()) {
          reactContext.sendBroadcast(new Intent(ACTION_USB_NOT_OPENED));
          return;
        }

        serialPorts.put(this.threadUsbDevice.getDeviceName(), serialPort);
        serialPort.setBaudRate(autoConnect ? autoConnectBaudRate : BAUD_RATE);
        serialPort.setDataBits(DATA_BIT);
        serialPort.setStopBits(STOP_BIT);
        serialPort.setParity(PARITY);
        serialPort.setFlowControl(FLOW_CONTROL);

        serialPort.read(new UsbSerialInterface.UsbReadCallback() {
          @Override
          public void onReceivedData(byte[] bytes) {
            if (bytes.length == 0) {
              return;
            }
            try {
              WritableArray uint8Array = new WritableNativeArray();
              for (byte b : bytes) {
                uint8Array.pushInt(UnsignedBytes.toInt(b));
              }
              WritableMap params = Arguments.createMap();
              params.putArray("payload", uint8Array);
              params.putString("deviceName", threadUsbDevice.getDeviceName());
              emitEvent(ON_READ_DATA_FROM_PORT, params);
            } catch (Exception err) {
              emitErrorEvent(Error.COULD_NOT_READ_DATA, "exceptionMessage", err.getMessage());
            }
          }
        });

        Intent connectedIntent = new Intent(ACTION_USB_CONNECTED);
        connectedIntent.putExtra(EXTRA_USB_DEVICE_NAME, this.threadUsbDevice.getDeviceName());
        reactContext.sendBroadcast(connectedIntent);
      } catch (Exception error) {
        emitErrorEvent(Error.CONNECTION_FAILED, "exceptionMessage", error.getMessage());
      }
    }
  }

  private void requestUsbPermission(UsbDevice device) {
    if (device == null)
      return;
    Intent intent = new Intent(ACTION_USB_PERMISSION);
    intent.putExtra(UsbManager.EXTRA_DEVICE, device);
    this.usbManager.requestPermission(device, PendingIntent.getBroadcast(reactContext, 0, intent, PendingIntent.FLAG_MUTABLE));
  }

  private void startConnection(UsbDevice device) {
    if (device == null) {
      emitErrorEvent(Error.DEVICE_NOT_FOUND);
      return;
    }

    UsbDeviceConnection conn = this.usbManager.openDevice(device);

    if (conn == null) {
      emitErrorEvent(Error.COULD_NOT_OPEN_SERIALPORT);
      return;
    }

    new ConnectionThread(device, conn).start();
  }

  private void stopConnection(UsbSerialDevice serialPort) {
    if (serialPort == null)
      return;

    serialPort.close();

    Intent intent = new Intent(ACTION_USB_DISCONNECTED);
    intent.putExtra(EXTRA_USB_DEVICE_NAME, serialPort.getUsbDevice().getDeviceName());
    reactContext.sendBroadcast(intent);
  }
}