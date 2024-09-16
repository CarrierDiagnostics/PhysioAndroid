package com.example.PhysioAndroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.util.Pair;

import com.example.physioandroid.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;


public class ScreenCaptureService extends Service {

    public static String chosenProbe;
    public static String orientation;
    private String[] poses;
    public Queue<byte[]> poseImagesToSend = new ConcurrentLinkedQueue<>();
    public  List<Long> timeList = new ArrayList<>();
    public List<Float> pitchList = new ArrayList<>();
    public List<JSONObject> poseJSONsToSend = new ArrayList<>();
    private int poseInt = 0;
    public static String bodyPart= "None";
    public int subBodyPart = 4;
    public boolean fanFlag = false;
    private static final String TAG = "ScreenCaptureService";
    private static final String RESULT_CODE = "RESULT_CODE";
    private static final String DATA = "DATA";
    private static final String ACTION = "ACTION";
    private static final String START = "START";
    private static final String STOP = "STOP";
    private static final String SCREENCAP_NAME = "screencap";


    private static int IMAGES_PRODUCED;

    private MediaProjection mMediaProjection;
    private String mStoreDir;
    private ImageReader mImageReader;
    private Handler mHandler;
    private Display mDisplay;
    private VirtualDisplay mVirtualDisplay;
    private int mDensity;
    private int mWidth;
    private int mHeight;
    private int mRotation;
    private OrientationChangeCallback mOrientationChangeCallback;
    long startFan;
    private boolean stopFan;

    static final String CHANNEL_ID = "Overlay_notification_channel";
    private static final int LayoutParamFlags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;

    private LayoutInflater inflater;
    //private Display mDisplay;
    private View layoutView;
    private View UIView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams UIparams;
    private WindowManager.LayoutParams appParams;
    public SocketThread socketThread;

    public static boolean closeApp = false;
    private String pt_number = "000000";


    public static Intent getStartIntent(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.putExtra(ACTION, START);
        intent.putExtra(RESULT_CODE, resultCode);
        intent.putExtra(DATA, data);
        return intent;
    }

    public static Intent getStopIntent(Context context) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.putExtra(ACTION, STOP);
        return intent;
    }

    private static boolean isStartCommand(Intent intent) {
        return intent.hasExtra(RESULT_CODE) && intent.hasExtra(DATA)
                && intent.hasExtra(ACTION) && Objects.equals(intent.getStringExtra(ACTION), START);
    }

    private static boolean isStopCommand(Intent intent) {
        return intent.hasExtra(ACTION) && Objects.equals(intent.getStringExtra(ACTION), STOP);
    }

    private static int getVirtualDisplayFlags() {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    }

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            FileOutputStream fos = null;
            Bitmap bitmap = null;
            try (Image image = mImageReader.acquireLatestImage()) {
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * mWidth;
                    bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    bitmap.recycle();

                   
                    if(fanFlag){
                        poseImagesToSend.add(byteArray);
                        IMAGES_PRODUCED++;
                        long time = System.currentTimeMillis()-startFan;
                        JSONObject dataToSend = new JSONObject();
                        dataToSend.put("startFan", startFan);
                        dataToSend.put("imageType","voxel");
                        dataToSend.put("target", bodyPart);
                        dataToSend.put("pt_number", pt_number);
                        dataToSend.put("orientation", orientation);

                        Log.i("fan data", " Image number " + IMAGES_PRODUCED + " at " + time + "with orientation " + orientation);
                        timeList.add(time);
                        if(stopFan){
                            try {
                                fanFlag = false;
                                dataToSend.put("timeList", timeList.toString());
                                Log.e("to send", "data to send = " + dataToSend);
                                socketThread.sendList((List<byte[]>) poseImagesToSend, dataToSend);
                                poseImagesToSend.clear();
                                IMAGES_PRODUCED=0;
                                if (orientation == "transverse"){
                                    orientation = "sagittal";
                                    ((TextView) UIView.findViewById(R.id.textView)).setText("Sagittal");
                                }
                            }catch (Exception e){
                                Log.e("return","Exceptiong = " + e);
                            }
                        }

                    }


                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                }

                if (bitmap != null) {
                    bitmap.recycle();
                }

            }
        }
    }

    private class OrientationChangeCallback extends OrientationEventListener {

        OrientationChangeCallback(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            final int rotation = mDisplay.getRotation();
            if (rotation != mRotation) {
                mRotation = rotation;
                try {
                    // clean up
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);

                    // re-create virtual display depending on device width / height
                    createVirtualDisplay();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);
                    if (mOrientationChangeCallback != null) mOrientationChangeCallback.disable();
                    mMediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
                }
            });
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    private void closeApp(View view){
        System.exit(0);
    }
    @Override
    public void onCreate() {
        super.onCreate();
//For Overlay
        int LAYOUT_FLAG;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_PHONE;
        }
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParamFlags,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.BOTTOM | Gravity.LEFT;
        params.x = 1000;
        params.y = 1000;
        windowManager = (WindowManager) this.getSystemService(WINDOW_SERVICE);
        appParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParamFlags,
                PixelFormat.TRANSPARENT);
        appParams.height = 500;
        appParams.gravity = Gravity.BOTTOM;

        UIparams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParamFlags,
                PixelFormat.TRANSPARENT);
        inflater = LayoutInflater.from(this);
        UIparams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        UIView = inflater.inflate(R.layout.ui, null);
        windowManager.addView(UIView, UIparams);

        mDisplay = windowManager.getDefaultDisplay();

        layoutView = inflater.inflate(R.layout.overlay, null);
        windowManager.addView(layoutView, params);


        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, getString(R.string.overlay_notification), NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.setSound(null, null);
            notificationManager.createNotificationChannel(notificationChannel);
            Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
            builder.setContentTitle(getString(R.string.overlay)).setContentText(getString(R.string.overlay_notification)).setSmallIcon(R.drawable.ic_launcher_background);
            startForeground(1, builder.build());
            params.x = 500;
            params.y = 500;
            windowManager.updateViewLayout(layoutView, params);
        }

        //For screen grab
        socketThread = new SocketThread();
        socketThread.start();


        // create store dir
        File externalFilesDir = getExternalFilesDir(null);
        if (externalFilesDir != null) {
            mStoreDir = externalFilesDir.getAbsolutePath() + "/screenshots/";
            File storeDirectory = new File(mStoreDir);
            if (!storeDirectory.exists()) {
                boolean success = storeDirectory.mkdirs();
                if (!success) {
                    Log.e(TAG, "failed to create file storage directory.");
                    stopSelf();
                }
            }
        } else {
            Log.e(TAG, "failed to create file storage directory, getExternalFilesDir is null.");
            stopSelf();
        }

        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mHandler = new Handler();
                Looper.loop();
            }
        }.start();

    };




    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isStartCommand(intent)) {
            // create notification
            Pair<Integer, Notification> notification = NotificationUtils.getNotification(this);
            startForeground(notification.first, notification.second);



            // start projection
            int resultCode = intent.getIntExtra(RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(DATA);
            startProjection(resultCode, data);
        } else if (isStopCommand(intent)) {
            stopProjection();
            stopSelf();
        } else {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent data) {
        MediaProjectionManager mpManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mMediaProjection == null) {
            mMediaProjection = mpManager.getMediaProjection(resultCode, data);
            if (mMediaProjection != null) {
                // display metrics
                mDensity = Resources.getSystem().getDisplayMetrics().densityDpi;
                WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                mDisplay = windowManager.getDefaultDisplay();

                // create virtual display depending on device width / height
                createVirtualDisplay();

                // register orientation change callback
                mOrientationChangeCallback = new OrientationChangeCallback(this);
                if (mOrientationChangeCallback.canDetectOrientation()) {
                    mOrientationChangeCallback.enable();
                }

                // register media projection stop callback
                mMediaProjection.registerCallback(new MediaProjectionStopCallback(), mHandler);
            }
        }
    }

    private void stopProjection() {
        if (mHandler != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mMediaProjection != null) {
                        mMediaProjection.stop();
                    }
                }
            });
        }
    }

    @SuppressLint("WrongConstant")
    private void createVirtualDisplay() {
        // get width and height
        mWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        mHeight = Resources.getSystem().getDisplayMetrics().heightPixels;

        // start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(SCREENCAP_NAME, mWidth, mHeight,
                mDensity, getVirtualDisplayFlags(), mImageReader.getSurface(), null, mHandler);
        mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mHandler);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        windowManager.removeView(layoutView);
    }
    public void StartFan(View view) {
        ((TextView) UIView.findViewById(R.id.textView)).setText("Fan Through");
        fanFlag = true;
        startFan = System.currentTimeMillis();
    }

    public void StopFan(View view){
        stopFan = true;
        if (orientation == "sagittal"){
            
            Intent returnIntent = new Intent(this, MainActivity.class);
            startActivity(returnIntent);
            stopProjection();
        }
    }
}

class SocketThread extends Thread {
    byte[] data;
    boolean curSending = false;
    public static Handler socketHandler;
    public static final int SERVER_PORT = 8007;

    private PrintWriter mBufferOut;
    private BufferedReader mBufferIn;
    public Socket socket = null;
    private boolean running = false;
    private int target = 1;
    public int subBodyPart = 2;
    @Override
    public void run() {
        super.run();
        //Log.e("socketthread","the thread has started");
        try {
            URL url = new URL("http://www.carriertech.uk");
            InetAddress serverAddr = InetAddress.getByName(url.getHost());
            socket = new Socket(serverAddr, SERVER_PORT);
            //Log.e("socketthread","the socket is = " + socket);
            mBufferIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        } catch (IOException e) {
            Log.e("socketthread","failed = " + e);
        }

    }
    public String sendData(String msg, byte[] byteArray) throws InterruptedException, IOException {
        String mServerMessage = "";
        //Log.e("messagesend","in thread and cursending is "+curSending);
        if (!curSending) {
            curSending = true;
            byte[] messageByte = new byte[20];

            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            try {
                dos.write(byteArray);
                dos.writeChars("ENDOFIMAGE");
                dos.writeUTF(msg);
                dos.writeChars("ENDOFFILE");
                int bytesRead = dis.read(messageByte);
                mServerMessage+= new String(messageByte, 0, bytesRead);

            } catch (IOException e) {
                Log.e("messagesend", String.valueOf(e));
                e.printStackTrace();
            }

        curSending = false;
        }
        return mServerMessage;
    }

    public void sendList(List<byte[]> poseImagesToSend, JSONObject poseJSONsToSend) throws IOException {
        try {
            Log.e("return", "made it to socket");

            String mServerMessage = "";
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            byte[] messageByte = new byte[10];
            int c = 0;
            for (byte[] image : poseImagesToSend) {
                c++;
                dos.write(image);
                dos.writeChars("ENDOFIMAGE");
                Log.i("socket", "sent image " + c);
            }
            Log.i("socket", "finished sending images");
            String DTS = poseJSONsToSend.toString();
            Log.i("socket", "to string = " + DTS);
            dos.writeUTF(DTS);
            Log.i("socket", "sent DTS");
            dos.writeChars("ENDOFFILE");
            Log.e("return", "sent");
        }catch (IOException e) {
            Log.e("return", String.valueOf(e));
            e.printStackTrace();
        }
        /*for (int i = 0; i < poseImagesToSend.size(); i++) {
            Log.e("return","sendingfile numer " + i);
            try {
                dos.write(poseImagesToSend.get(i));
                dos.writeChars("ENDOFIMAGE");
                String msg = poseJSONsToSend.get(i).toString();
                dos.writeUTF(msg);
                dos.writeChars("ENDOFFILE");
                int bytesRead = dis.read(messageByte);
                mServerMessage+= new String(messageByte, 0, bytesRead);
            } catch (IOException e) {
                Log.e("return", String.valueOf(e));
                e.printStackTrace();
            }
        }*/
    }
}
