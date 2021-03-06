package com.huahao.weight.activity;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageRequest;
import com.huahao.weight.HttpUtils;
import com.huahao.weight.InitApplication;
import com.huahao.weight.NoneReconnect;
import com.huahao.weight.R;
import com.huahao.weight.bean.HandShake;
import com.huahao.weight.utils.CommonUtils;
import com.huahao.weight.utils.VToast;
import com.huahao.weight.volley.RequestListener;
import com.huahao.weight.volley.StringRequest;
import com.kongqw.serialportlibrary.SerialPortManagerTz;
import com.kongqw.serialportlibrary.Tool;
import com.kongqw.serialportlibrary.listener.OnOpenSerialPortListener;
import com.kongqw.serialportlibrary.listener.OnSerialPortDataListener;
import com.xuhao.android.libsocket.sdk.ConnectionInfo;
import com.xuhao.android.libsocket.sdk.OkSocketOptions;
import com.xuhao.android.libsocket.sdk.SocketActionAdapter;
import com.xuhao.android.libsocket.sdk.bean.IPulseSendable;
import com.xuhao.android.libsocket.sdk.bean.ISendable;
import com.xuhao.android.libsocket.sdk.bean.OriginalData;
import com.xuhao.android.libsocket.sdk.connection.IConnectionManager;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.Charset;
import java.util.ArrayList;

import static com.xuhao.android.libsocket.sdk.OkSocket.open;

/**
 * 扫码开门
 */
public class MainActivity extends YBaseActivity implements View.OnClickListener, OnOpenSerialPortListener {

    private String fan_id, type;
    private String gpioOut = "234";
    private String gpioIn = "203";
    private IConnectionManager mManager;
    private ConnectionInfo mInfo;
    private OkSocketOptions mOkOptions;
    private int numberIn = 0;//表示某次的读取次数三次表示失败
    private int msgId;
    private ImageView imageView;
    private ListView listView;
    private ArrayList<String> list = new ArrayList<>();
    private ArrayAdapter arrayAdapter;
    private SerialPortManagerTz mSerialPortManager;

    @Override
    protected int getContentView() {
        return R.layout.activity_main;
    }

    @Override
    protected void initView() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN); //设置全屏的flag
        app.addActivity(this);
        imageView = $(R.id.imageView);
        listView = $(R.id.listView);
        mInfo = new ConnectionInfo(HttpUtils.TCP_IP, HttpUtils.TCP_PRO_IP);
        mOkOptions = new OkSocketOptions.Builder(OkSocketOptions.getDefault())
                .setReconnectionManager(new NoneReconnect())
                .setSinglePackageBytes(1024)
                .build();
        mManager = open(mInfo, mOkOptions);
        mManager.registerReceiver(adapter);
        mManager.connect();
        checkPermission(new String[]{Manifest.permission.READ_PHONE_STATE}, 199);
        Toast.makeText(this, "扫码开门", Toast.LENGTH_LONG).show();
    }

    public void checkPermission(String[] permissions, int REQUEST_FOR_PERMISSIONS) {
        if (lacksPermissions(permissions)) {
            ActivityCompat.requestPermissions(this,
                    permissions,
                    REQUEST_FOR_PERMISSIONS);
        } else {
            HttpUtils.IMEI = CommonUtils.getSubscriberId(this);
        }
    }

    @Override
    protected void initData() {
        gpioIn();
        gpioOut();
        arrayAdapter = new ArrayAdapter<>(this, R.layout.my_spinner, list);
        listView.setAdapter(arrayAdapter);
        mSerialPortManager = new SerialPortManagerTz();
        mSerialPortManager.setOnOpenSerialPortListener(this)
                .setOnSerialPortDataListener(new OnSerialPortDataListener() {
                    @Override
                    public void onDataReceived(final byte[] bytes) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                addData("串口收到：" + new String(bytes) + "-----" + Tool.bytesToHexString(bytes));
                            }
                        });
                    }

                    @Override
                    public void onDataSent(final byte[] bytes) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                addData("串口发出：" + Tool.bytesToHexString(bytes));
                            }
                        });
                    }
                }).openSerialPort(new File("/dev/ttyS3"), 9600);
    }

    public RequestListener<String> listener = new RequestListener<String>() {
        @Override
        protected void onSuccess(int what, String response) {
            JSONObject jsonObject;
            switch (what) {
                case 1001:
                    jsonObject = (JSONObject) JSON.parse(response);
                    if (jsonObject.getInteger("status") == 0) {
                        ImageRequest request = new ImageRequest(jsonObject.getString("device_qrcode"),
                                new Response.Listener<Bitmap>() {
                                    @Override
                                    public void onResponse(Bitmap bitmap) {
                                        imageView.setImageBitmap(bitmap);
                                    }
                                }, 0, 0, Bitmap.Config.RGB_565,
                                new Response.ErrorListener() {
                                    public void onErrorResponse(VolleyError error) {
                                        imageView.setImageResource(R.mipmap.ic_launcher);
                                    }
                                });
                        app.mQueue.add(request);
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        protected void onError(int what, int code, String message) {
        }
    };


    @Override
    protected void initListener() {
        findViewById(R.id.button_open).setOnClickListener(this);
        findViewById(R.id.button_close).setOnClickListener(this);
    }

    @Override
    protected boolean isApplyEventBus() {
        return false;
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.button_close:
                controlGpio(false);
                mHandler.postDelayed(mRunnable, 1000);
                break;
            case R.id.button_open:
                controlGpio(true);
                mHandler.postDelayed(mRunnable, 1000);
                break;
        }
    }

    private void gpioOut() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream dos = new DataOutputStream(process.getOutputStream());
            //打开gpio引脚74，即status_led连接的引脚
            dos.writeBytes("echo " + gpioOut + " > /sys/class/gpio/export" + "\n");
            dos.flush();
            //设置引脚功能为输出
            dos.writeBytes("echo out > /sys/class/gpio/gpio" + gpioOut + "/direction" + "\n");
            dos.flush();
            dos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void controlGpio(boolean isOpen) {
        DataOutputStream dos = null;
        try {
            Process process = Runtime.getRuntime().exec("su");
            dos = new DataOutputStream(process.getOutputStream());
            if (isOpen)
                dos.writeBytes("echo 1 > /sys/class/gpio/gpio" + gpioOut + "/value" + "\n");//开
            else
                dos.writeBytes("echo 0 > /sys/class/gpio/gpio" + gpioOut + "/value" + "\n");//关
            dos.flush();
            dos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void gpioIn() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream dos = new DataOutputStream(process.getOutputStream());
            //打开gpio引脚74，即status_led连接的引脚
            dos.writeBytes("echo " + gpioIn + " > /sys/class/gpio/export" + "\n");
            dos.flush();
            //设置引脚功能为输出
            dos.writeBytes("echo in > /sys/class/gpio/gpio" + gpioIn + "/direction" + "\n");
            dos.flush();
            dos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String readGpio() {
        String gpioState = null;
        String str = "";
        try {
            Process pp = Runtime.getRuntime().exec("cat /sys/class/gpio/gpio" + gpioIn + "/value");
            InputStreamReader ir = new InputStreamReader(pp.getInputStream());
            LineNumberReader input = new LineNumberReader(ir);
            for (; null != str; ) {
                str = input.readLine();
                if (str != null) {
                    gpioState = str.trim();
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return gpioState;
    }

    private Handler mHandler = new Handler();
    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            String gpioState = readGpio();
            if (!TextUtils.isEmpty(gpioState)) {
                if (gpioState.equals("1")) {
                    socketSend(HttpUtils.getDoor(fan_id, HttpUtils.IMEI, type, "0"));
                    mHandler.postDelayed(mRunnableValue, 100);
                } else {
                    controlGpio(false);
                    isFalse();
                }
                addData("GPIO：" + gpioState);
            } else {
                isFalse();
                addData("GPIO：读取失败");
            }
        }
    };

    private void isFalse() {
        if (numberIn < 2) {
            mHandler.postDelayed(mRunnable, 500);
            numberIn++;
        } else {
            numberIn = 0;
            socketSend(HttpUtils.getDoor(fan_id, HttpUtils.IMEI, type, "1"));
        }
    }

    private Runnable mRunnableValue = new Runnable() {
        @Override
        public void run() {
            String gpioState = readGpio();
            addData("GPIO：" + gpioState);
            if (gpioState.equals("0")) {
                controlGpio(false);
            } else {
                mHandler.postDelayed(mRunnableValue, 500);
            }
        }
    };


    private Runnable mRunnableError = new Runnable() {
        @Override
        public void run() {
            mManager.connect();
        }
    };
    private Runnable mRunnableCSQ = new Runnable() {
        @Override
        public void run() {
            socketSend(HttpUtils.getCSQ(msgId, HttpUtils.IMEI));
            msgId++;
            mHandler.postDelayed(mRunnableCSQ, 300000);
        }
    };
    private SocketActionAdapter adapter = new SocketActionAdapter() {

        @Override
        public void onSocketConnectionSuccess(Context context, ConnectionInfo info, String action) {
            Log.i("ywl", "onSocketConnectionSuccess:");
            addData("TCP连接成功...");
            socketSend(HttpUtils.getCheckIn(0, HttpUtils.IMEI));
            mHandler.removeCallbacks(mRunnableCSQ);
            mHandler.postDelayed(mRunnableCSQ, 1000);
            StringRequest request = HttpUtils.getImageCode(listener);
            InitApplication.getInstance().addRequestQueue(1001, request, this);
        }

        @Override
        public void onSocketDisconnection(Context context, ConnectionInfo info, String action, Exception e) {
            if (e != null) {
                mHandler.postDelayed(mRunnableError, 20000);
            }
        }

        @Override
        public void onSocketConnectionFailed(Context context, ConnectionInfo info, String action, Exception e) {
            mHandler.postDelayed(mRunnableError, 20000);
        }

        //接收
        @Override
        public void onSocketReadResponse(Context context, ConnectionInfo info, String action, OriginalData data) {
            super.onSocketReadResponse(context, info, action, data);
            String str = new String(data.getBodyBytes(), Charset.forName("utf-8"));
            ReadResponse(str);
            addData("TCP接收：" + str);
            Log.i("ywl", "onSocketReadResponse:" + str);
        }

        @Override
        public void onSocketWriteResponse(Context context, ConnectionInfo info, String action, ISendable data) {
            super.onSocketWriteResponse(context, info, action, data);
        }

        @Override
        public void onPulseSend(Context context, ConnectionInfo info, IPulseSendable data) {
            super.onPulseSend(context, info, data);
        }
    };

    private void socketSend(String tcpMap) {
        addData("TCP发送：" + tcpMap);
        if (!mManager.isConnect()) {
            mManager.connect();
        } else {
            mManager.send(new HandShake(tcpMap));
        }
    }

    private void ReadResponse(String str) {
        JSONObject jsonObject = new JSONObject();
        String[] sourceStrArray = str.split("&");
        for (int i = 0; i < sourceStrArray.length; i++) {
            String[] jsonStr = sourceStrArray[i].split("=");
            if (jsonStr.length == 2) {
                jsonObject.put(jsonStr[0], jsonStr[1]);
            }
        }
        switch (jsonObject.getString("Action")) {
            case "Door":
                fan_id = jsonObject.getString("fan_id");
                type = jsonObject.getString("type");
                if ("1".equals(type)) {
                    controlGpio(true);
                } else {
                    controlGpio(false);
                }
                mHandler.postDelayed(mRunnable, 1000);
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case 199:
                for (int i = 0; i < grantResults.length; i++) {
                    if (i == 0 && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        HttpUtils.IMEI = CommonUtils.getSubscriberId(this);
                    }
                }
                break;
            default:
                break;
        }
    }


    @Override
    protected void onDestroy() {
        mManager.disConnect();
        mHandler.removeCallbacks(mRunnableCSQ);
        mSerialPortManager.closeSerialPort();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true);
        }
        return super.onKeyDown(keyCode, event);
    }

    private void addData(String str) {
        if (list.size() > 50) {
            list.remove(0);
        }
        list.add(str);
        arrayAdapter.notifyDataSetChanged();
    }

    @Override
    public void onSuccess(File device) {
        VToast.showLong("串口打开成功");
    }

    @Override
    public void onFail(File device, Status status) {
        switch (status) {
            case NO_READ_WRITE_PERMISSION:
                VToast.showLong("没有读写权限");
                break;
            case OPEN_FAIL:
            default:
                //TODO 打开失败后向服务器发送信息
                VToast.showLong("串口打开失败");
                break;
        }
    }
}
