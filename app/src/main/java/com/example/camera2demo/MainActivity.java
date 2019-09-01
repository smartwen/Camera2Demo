package com.example.camera2demo;
//https://blog.csdn.net/lb377463323/article/details/52740411
//https://blog.csdn.net/qq_38106356/article/details/77996319
//https://blog.csdn.net/qq_34884729/article/details/53284274
//https://github.com/googlesamples/android-Camera2Video/

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;
import android.Manifest.permission.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.jar.Manifest;

public class MainActivity extends AppCompatActivity {
    private static String TAG = "Camera2";
    private CameraManager mCameraManager;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceViewHolder;
    private Handler mHandler;
    private HandlerThread handlerThread;
    private String mCameraId;
    private ImageReader mImageReader;
    private CameraDevice mCameraDevice;
    private CaptureRequest.Builder mPreviewBuilder;
    private CameraCaptureSession mSession;
    private ImageView img_show;
    private Button take_picture_bt;
    private Button take_video_bt;
    private Button open_Camera_bt;
    private Button result_bt;

    private Handler mainHandler;
    private int pictureId = 0;
    private File mImageFile;
    private static final int CHOOSE_PHOTO = 1;
    private static final int RESULT_CODE_STARTAUDIO = 2;
    private static final int RESULT_CODE_WRITE_EXTERNAL_STORAGE = 3;
    private boolean mIsRecordingVideo; //开始停止录像

    private MediaRecorder mMediaRecorder;
    private String mNextVideoAbsolutePath;
    private Integer mSensorOrientation;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    private CameraCharacteristics cameraCharacteristics;
    /**
     * 防止应用程序在关闭相机前退出。
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    /**
     * The {@link android.util.Size} of video recording.
     */
    private Size mVideoSize;
    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;//逆向

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.i(TAG, "1.onCreate");
        initWidget();

    }

    private void initWidget() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        mCameraManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
        img_show = (ImageView) findViewById(R.id.img_show);
        take_picture_bt = (Button) findViewById(R.id.take_picture);
        take_video_bt = (Button) findViewById(R.id.take_video);
        result_bt = findViewById(R.id.recognition_result);
        open_Camera_bt = findViewById(R.id.openCamera);
        initSurfaceView();//初始化SurfaceView
        /**
         * 拍照、结果按钮监听
         */
        take_picture_bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takePicture();
            }
        });
        result_bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAlbum();
            }
        });
        take_video_bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mIsRecordingVideo) {
                    stopRecordingVideo();
                } else {
                    startRecordingVideo();
                }
            }
        });
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            closePreviewSession();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }

            if (mSession != null) {
                mSession.stopRepeating();
                mSession.abortCaptures();
                mSession.close();
                mSession = null;
            }

            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } catch (CameraAccessException e) {
            Log.e(TAG,"为相机创建一个CameraCaptureSession 停止预览");
            e.printStackTrace();
        }finally {
            mCameraOpenCloseLock.release();
        }
    }

    public void initSurfaceView() {
        Log.i(TAG, "2.initSurfaceView");
        mSurfaceView = findViewById(R.id.mFirstSurfaceView);
        mSurfaceViewHolder = mSurfaceView.getHolder();//通过SurfaceViewHolder可以对SurfaceView进行管理
        mSurfaceViewHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                initCameraAndPreview();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                //释放camera
                if (mCameraDevice != null) {
                    mCameraDevice.close();
                    mCameraDevice = null;
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }
        });
    }

    private void startBackgroundThread() {
        handlerThread = new HandlerThread("My First Camera2");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mainHandler = new Handler(getMainLooper());//用来处理ui线程的handler，即ui线程
    }

    private void stopBackgroundThread() {
        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            mHandler = null;
            mainHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 初始化相机
     */
    @TargetApi(19)
    public void initCameraAndPreview() {
        mMediaRecorder = new MediaRecorder();
        Log.d(TAG, "3.initCameraAndPreview");
        final Activity activity = MainActivity.this;
        if (null == activity || activity.isFinishing()) {
            return;
        }
        startBackgroundThread();
        try {
            Log.d(TAG, "tryAcquire");
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            mCameraId = "" + CameraCharacteristics.LENS_FACING_FRONT;
            mImageReader = ImageReader.newInstance(mSurfaceView.getWidth(), mSurfaceView.getHeight(), ImageFormat.JPEG,/*maxImages*/7);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mainHandler);//这里必须传入mainHandler，因为涉及到了Ui操作

            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (map == null) {
                throw new RuntimeException("Cannot get available preview/video sizes");
            }

            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    mSurfaceView.getWidth(), mSurfaceView.getHeight(), mVideoSize);
            configureTransform(mSurfaceView.getWidth(), mSurfaceView.getHeight());
            openCamera();
        } catch (CameraAccessException e) {
            Toast.makeText(MainActivity.this, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
            activity.finish();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.

        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }


    private void openCamera() {
        try {
            Log.d(TAG, "4.openCamera");
            Thread.sleep(2000);
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA}, CHOOSE_PHOTO);
            }
            mCameraManager.openCamera(mCameraId, deviceStateCallback, mHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(MainActivity.this, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }


    private CameraDevice.StateCallback deviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.d(TAG, "5.CameraDevice.StateCallback deviceStateCallback onOpened ");
            mCameraDevice = camera;
            takePreview();
            mCameraOpenCloseLock.release();
            if (null != mSurfaceView) {
                configureTransform(mSurfaceView.getWidth(), mSurfaceView.getHeight());
            }
        }


        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            mCameraOpenCloseLock.release();
            Log.d(TAG, "5.CameraDevice.StateCallback deviceStateCallback onError ");
            Toast.makeText(MainActivity.this, "打开摄像头失败", Toast.LENGTH_SHORT).show();
        }
    };

    public void takePreview() {
        Log.d(TAG, "6.takePreview ");
        if (null == mCameraDevice ) {
            return;
        }
        try {
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            //在开启预览之前，设置ImageReader为输出Surface
            // ImageReader的onImageAvailable()方法会回调
            mPreviewBuilder.addTarget(mSurfaceViewHolder.getSurface());
            mCameraDevice.createCaptureSession(Arrays.asList(mSurfaceViewHolder.getSurface(),
                    mImageReader.getSurface()), mSessionPreviewStateCallback, mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    public void startPreview() {
        Log.d(TAG, "6.video startPreview ");
        if (null == mCameraDevice ) {
            return;
        }
        try {
            closePreviewSession();
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewBuilder.addTarget(mSurfaceViewHolder.getSurface());
//            mCameraDevice.createCaptureSession(Arrays.asList(mSurfaceViewHolder.getSurface(),
//                    mImageReader.getSurface()), mSessionPreviewStateCallback, mHandler);
            mCameraDevice.createCaptureSession(Arrays.asList(mSurfaceViewHolder.getSurface(), mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mSession = session;
                            updatePreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Activity activity = MainActivity.this;
                            if (null != activity) {
                                Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }, mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraCaptureSession.StateCallback mSessionPreviewStateCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    mSession = session;
                    //配置完毕开始预览
                    try {
                        /**
                         * 设置你需要配置的参数
                         */
                        Log.d(TAG, "7.CameraCaptureSession.StateCallback mSessionPreviewStateCallback onConfigured ");
                        //自动对焦
                        mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        //打开闪光灯
                        mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                        setUpCaptureRequestBuilder(mPreviewBuilder);//录像用
                        //设置反复捕获数据的请求，这样预览界面就会一直有数据显示
                        Log.d(TAG, "8.CameraCaptureSession.StateCallback mSessionPreviewStateCallback setRepeatingRequest ");
                        mSession.setRepeatingRequest(mPreviewBuilder.build(), null, mHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "7.CameraCaptureSession.StateCallback mSessionPreviewStateCallback onConfigureFailed ");
                    Toast.makeText(MainActivity.this, "配置失败", Toast.LENGTH_SHORT).show();
                }
            };

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in 在确定相机预览大小之前，不应调用此方法
     * 直到“mTextureview”的大小固定为止才openCamera
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {

        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        float scale = 0;
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
//        mSurfaceViewHolder.setFixedSize(scale,scale);


    }

    //这个回调接口用于拍照结束时重启预览，因为拍照会导致预览停止
    private CameraCaptureSession.CaptureCallback mSessionCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            Log.d(TAG, "10.CameraCaptureSession.CaptureCallback onCaptureCompleted ");
            mSession = session;
            //重启预览
            restartPreview();
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            mSession = session;
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
        }
    };


    public void takePicture() {
        Log.d(TAG, "9.takePicture ");
        try {

            CaptureRequest.Builder captureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);//用来设置拍照请求的request
            captureRequestBuilder.addTarget(mImageReader.getSurface());
            // 自动对焦
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            // 自动曝光
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            CameraCharacteristics cameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(cameraCharacteristics, rotation));//使图片做顺时针旋转
            CaptureRequest mCaptureRequest = captureRequestBuilder.build();
//            mSession.capture(mCaptureRequest, null, mHandler);//最关键的一步，通过这步，拍照动作才真正完成
            //停止预览
            mSession.stopRepeating();
            //开始拍照，然后回调上面的接口重启预览，因为mCaptureBuilder设置ImageReader作为target，所以会自动回调ImageReader的onImageAvailable()方法保存图片
            Log.d(TAG, "9.capture ");
            mSession.capture(mCaptureRequest, mSessionCaptureCallback, mHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 开始录像
     */
    public void startRecordingVideo() {
        Log.d(TAG, "9.startRecordingVideo ");
        if (null == mCameraDevice || null == mSurfaceView) {
            return;
        }
        try {

            if (PackageManager.PERMISSION_GRANTED == ContextCompat.
                    checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)) {
            } else {
                //提示用户开户权限音频
                String[] perms = {"android.permission.RECORD_AUDIO"};
                ActivityCompat.requestPermissions(MainActivity.this, perms, RESULT_CODE_STARTAUDIO);
            }

            closePreviewSession();
            setUpMediaRecorder();

            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();

            Surface previewSurface = mSurfaceView.getHolder().getSurface();
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            // Set up Surface for the MediaRecorder设置MediaRecorder的表面
            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);

            // 一旦会话开始，我们就可以更新UI并开始录制
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mSession = cameraCaptureSession;
                    updatePreview();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Log.d(TAG, "9-3.onConfigured run ");
                            // UI
                            take_video_bt.setText("Stop");
                            mIsRecordingVideo = true;
                            //开启录像
                            mMediaRecorder.start();
                        }
                    });
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Log.d(TAG, "9-3.onConfigureFailed Failed to create capture session configuration failed ");
                    Toast.makeText(MainActivity.this, "startRecordingVideo配置失败", Toast.LENGTH_SHORT).show();
                }
            }, mainHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    /**
     * 停止录像
     */
    public void stopRecordingVideo() {
        // UI
        mIsRecordingVideo = false;
        take_video_bt.setText("Record");
        closePreviewSession();
        if (mMediaRecorder != null) {
            try {
                mMediaRecorder.stop();
            } catch (IllegalStateException e) {
                // TODO 如果当前java状态和jni里面的状态不一致，
                mMediaRecorder = null;
                mMediaRecorder = new MediaRecorder();
            }
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
        try {
            Activity activity = MainActivity.this;
            if (null != activity) {
                Toast.makeText(activity, "Video saved: " + mNextVideoAbsolutePath,
                        Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Video saved: " + mNextVideoAbsolutePath);
            }
            startPreview();
        } catch (IllegalStateException e) {
            // TODO 如果当前java状态和jni里面的状态不一致，
            Log.d(TAG, Log.getStackTraceString(e));
        } catch (RuntimeException e) {
            // TODO: handle exception
            Log.d(TAG, Log.getStackTraceString(e));
        } catch (Exception e) {
            // TODO: handle exception
            Log.d(TAG, Log.getStackTraceString(e));
        }

    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
//        if (mSurfaceView.isActivated()) {
//            openCamera();
//        }
//        else {
//            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
//        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void closePreviewSession() {
        Log.d(TAG, "9-1.closePreviewSession ");
/**
 * 在MediaRecorder停止前，停止相机预览，防止抛出serious error异常。
 */
        try {
            if (mSession != null) {
                mSession.stopRepeating();
                mSession.abortCaptures();
                mSession.close();
                mSession = null;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG,"为相机创建一个CameraCaptureSession 停止预览");
            e.printStackTrace();
        }
    }

    /**
     * 录制前，初始化
     */
    private void setUpMediaRecorder() {
        Log.d(TAG, "9-2.setUpMediaRecorder ");
        try {
            if (mMediaRecorder == null) {
                mMediaRecorder = new MediaRecorder();
            }else{
//                stopRecordingVideo();
                mMediaRecorder = new MediaRecorder();
            }
            mMediaRecorder.reset();
            // Step 3: Set a CamcorderProfile (requires API Level 8 or higher)
            CamcorderProfile profile = CamcorderProfile
                    .get(CamcorderProfile.QUALITY_480P);

            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
                mNextVideoAbsolutePath = getVideoFilePath();
            }
            mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
            mMediaRecorder.setVideoEncodingBitRate(10000000);
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            Log.d(TAG, profile.videoFrameWidth + "");
            Log.d(TAG, profile.videoFrameHeight + "");
            mMediaRecorder.setVideoSize(profile.videoFrameWidth,
                    profile.videoFrameHeight);// 设置分辨率：
            mMediaRecorder.setVideoFrameRate(30);
            mMediaRecorder.setPreviewDisplay(mSurfaceViewHolder.getSurface());
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            switch (mSensorOrientation) {
                case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                    mMediaRecorder.setOrientationHint(ORIENTATIONS.get(rotation));
                    break;
                case SENSOR_ORIENTATION_INVERSE_DEGREES:
                    mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                    break;
            }
            mMediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getVideoFilePath() {

        if (PackageManager.PERMISSION_GRANTED == ContextCompat.
                checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
        } else {
            //提示用户开户权限音频
            String[] perms = {"android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"};
            ActivityCompat.requestPermissions(MainActivity.this, perms, RESULT_CODE_WRITE_EXTERNAL_STORAGE);
        }
        final File dir = MainActivity.this.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + System.currentTimeMillis() + ".mp4";
    }

    private void updatePreview() {
        Log.d(TAG, "onConfigured.updatePreview ");
        if (null == mCameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            mSession.setRepeatingRequest(mPreviewBuilder.build(), null, mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void restartPreview() {
        try {
            Log.d(TAG, "11.restartPreview ");
            //执行setRepeatingRequest方法就行了，注意mCaptureRequest是之前开启预览设置的请求
            mSession.setRepeatingRequest(mPreviewBuilder.build(), null, mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //获取图片应该旋转的角度，使图片竖直
    private int getJpegOrientation(CameraCharacteristics c, int deviceOrientation) {
        if (deviceOrientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN)
            return 0;
        int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);

        // Round device orientation to a multiple of 90
        deviceOrientation = (deviceOrientation + 45) / 90 * 90;

        // LENS_FACING相对于设备屏幕的方向,LENS_FACING_FRONT相机设备面向与设备屏幕相同的方向
        boolean facingFront = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
        if (facingFront) deviceOrientation = -deviceOrientation;

        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        int jpegOrientation = (sensorOrientation + deviceOrientation + 360) % 360;

        return jpegOrientation;
    }

    public void openAlbum() {
        Intent intent = new Intent("android.intent.action.GET_CONTENT");//选择照片后毁掉onActivityResult方法
        intent.setType("image/*");
        startActivityForResult(intent, CHOOSE_PHOTO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case CHOOSE_PHOTO:
                String imgPath = null;
                if (resultCode == RESULT_OK) {
                    if (Build.VERSION.SDK_INT > 19) {
                        imgPath = handlerImgOnNewVersion(data);
                    } else {
                        imgPath = handlerImgOnOldVersion(data);
                    }
                    //以上获取了选择的图片的路径，在这里可以应用这个路径，做一些想要做的东西
                }
                break;
            default:
        }
    }

    @Override
    public void onRequestPermissionsResult(int permsRequestCode, String[] permissions, int[] grantResults) {
        switch (permsRequestCode) {
            case RESULT_CODE_STARTAUDIO:
                boolean albumAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                if (!albumAccepted) {
                    Toast.makeText(MainActivity.this, "请开启应用录音权限", Toast.LENGTH_SHORT).show();
                }
                break;
            case RESULT_CODE_WRITE_EXTERNAL_STORAGE:
                boolean albumAccepted2 = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                if (!albumAccepted2) {
                    Toast.makeText(MainActivity.this, "请开启写内存权限", Toast.LENGTH_SHORT).show();
                }
                break;

        }
    }

    private String handlerImgOnOldVersion(Intent data) {
        Uri uri = data.getData();
        String imgPath = getImagePath(uri, null);
        return imgPath;
    }

    private String handlerImgOnNewVersion(Intent data) {
        String imgPath = null;//选择的图片的路径
        Uri uri = data.getData();//选择图片的结果,即图片地址的封装，接下来对其进行解析
        if (DocumentsContract.isDocumentUri(this, uri)) {//判断是否是document类型
            String docId = DocumentsContract.getDocumentId(uri);
            switch (uri.getAuthority())//就是获取uri的最开头部分
            {
                case "com.android.providers.media.documents":
                    String id = docId.split(":")[1];//解析出数字格式的id
                    String selection = MediaStore.Images.Media._ID + "=" + id;
                    imgPath = getImagePath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection);
                    break;
                case "com.android.providers.downloads.documents":
                    Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), Long.valueOf(docId));
                    imgPath = getImagePath(contentUri, null);
                    break;
                default:
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            imgPath = getImagePath(uri, null);
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            imgPath = uri.getPath();
        }
        return imgPath;
    }

    private String getImagePath(Uri uri, String selection) {
        String path = null;
        Cursor cursor = getContentResolver().query(uri, null, selection, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA));
            }
            cursor.close();
        }
        return path;
    }
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }


    /**
     * 我们选择3x4宽高比的视频大小
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    private static Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }
    private ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.d(TAG, "9-2.onImageAvailable");

            //执行图像保存子线程
            mHandler.post(new ImageSaver(reader.acquireNextImage()));
//            image.close();
        }
    };
    public class ImageSaver implements Runnable {
        private Image mImage;

        public ImageSaver(Image image) {
            mImage = image;
        }

        @Override
        public void run() {
            Log.d(TAG, "9-3. save image imageSaver  run()");
            pictureId++;
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);

            mImageFile = new File(Environment.getExternalStorageDirectory() + "/DCIM/Camera/Camera2Demo_picture" + pictureId + ".jpg");
            FileOutputStream fos = null;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);//图像数据被转化为bitmap
            final Bitmap bitmap2 = BitmapFactory.decodeByteArray(data, 0, data.length);//图像数据被转化为bitmap

            if (bitmap != null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        img_show.setImageBitmap(bitmap2);
                    }
                });
            }

            try {
                fos = new FileOutputStream(mImageFile);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);//第二个参数为压缩质量
                fos.flush();
                fos.close();
                Log.d(TAG, "bitmap.compress ok");
            } catch (NullPointerException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImageFile = null;
                if (fos != null) {
                    try {
                        fos.close();
                        fos = null;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            //及时更新到系统相册
            MediaScannerConnection.scanFile(MainActivity.this, new String[]{Environment.getExternalStorageDirectory() + "/DCIM/Camera/Camera2Demo_picture" + pictureId + ".jpg"}, null, null);//"//"可以用File.separator代替

        }
    }

}
