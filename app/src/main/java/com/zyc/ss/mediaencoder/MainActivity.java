package com.zyc.ss.mediaencoder;

import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    final String TAG = getClass().getSimpleName();
    private MediaCodec mediaCodec;
    private Camera camera;
    private SurfaceHolder surfaceHolder;
    private LinkedBlockingDeque<byte[]> frameQueue = new LinkedBlockingDeque<>();
    private EncodeRunnable runnable;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surface_view);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        String[] permissions = {android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.CAMERA};
        ActivityCompat.requestPermissions(this, permissions, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        for (String permission : permissions) {
            Log.e("permission", permission);
        }
        for (int result : grantResults) {
            Log.e("grantResult", result + "");
        }
        if (grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            camera = getCamera(0);
            if (camera != null) {
                camera.setPreviewCallback(this);
               // camera.setDisplayOrientation(90);
                Camera.Parameters parameters = camera.getParameters();
                parameters.setPreviewFormat(ImageFormat.NV21);
                List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
                parameters.setPreviewSize(1280, 720);
                camera.setParameters(parameters);
                try {
                    camera.setPreviewDisplay(surfaceHolder);
                    camera.startPreview();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                startEncode();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.e(TAG, "onSurfaceCreated");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.e(TAG, "surfaceChanged:" + "format=" + format + ",width=" + width + ",height=" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.e(TAG, "surfaceDestroyed");
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
        if (runnable != null) {
            runnable.shutDown();
        }

    }

    public Camera getCamera(int cameraId) {
        Camera camera = null;
        try {
            camera = Camera.open(cameraId);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return camera;
    }

    private int frameNum;
    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        try {
            Log.d("putData", data.length+",frameNum"+(++frameNum));
            if (false) {
                  frameQueue.put(data);
            }

        } catch (InterruptedException e) {
            e.printStackTrace();
            Log.e("putToQueueErr", "-->");
        }
    }


    public void startEncode() {
        runnable = new EncodeRunnable();
        Thread thread = new Thread(runnable);
        thread.start();
    }

    public class EncodeRunnable implements Runnable {
        private int width = 1280;
        private int height = 720;
        int biterate = 8500*1000;
        long pts =  0;
        long generateIndex = 0;

        private OutputStream outputStream;

        public EncodeRunnable() {
            createFile();
            createMediaCodec();
        }

        private boolean flag = true;

        private void createMediaCodec() {
            MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", 1280, 720);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 5);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);;
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            try {
                mediaCodec = MediaCodec.createEncoderByType("video/avc");
            } catch (IOException e) {
                Log.e("createMediaCodec", "failed");
                e.printStackTrace();
            }
            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mediaCodec.start();

        }

        private void createFile() {
            File file = new File(Environment.getExternalStorageDirectory().getPath() + "/aaa.h264");
            Log.e("filePath", file.getAbsolutePath());
                try {
                    boolean newFile = file.createNewFile();
                    outputStream = new FileOutputStream(file);
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }


        @Override
        public void run() {
            while (flag) {
                byte[] inputData=null;
                try {
                    Log.d("frameQueueSize", frameQueue.size() + "");
                    inputData= frameQueue.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
                int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
                if (inputBufferIndex >= 0) {
                    pts = computePresentationTime(generateIndex);
                    ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        inputBuffer.clear();
                        inputBuffer.put(inputData,0,inputData.length);
                        mediaCodec.queueInputBuffer(inputBufferIndex,0,inputData.length,pts,1);
                    generateIndex += 1;
                    Log.d("inputBufferIndex", inputBufferIndex + "pst="+pts);
                    }else {
                    Log.e("inputBufferId<0", inputBufferIndex + "");
                }


                ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                int outputBufferIndex ;
                if ((outputBufferIndex=mediaCodec.dequeueOutputBuffer(bufferInfo,10000))>= 0) {
                    ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                    byte[] outData = new byte[bufferInfo.size];
                    outputBuffer.get(outData);
                    try {
                        Log.e("writebyte",outData.length+"");
                        outputStream.write(outData);
                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                    } catch (IOException e) {
                        Log.e("writeData", "failed");
                        e.printStackTrace();
                    }
                }
                Log.d("outputBufferIndex", outputBufferIndex + "");


            }
        }

        public void shutDown() {
            flag = false;
        }

        private long computePresentationTime(long frameIndex) {
            return  frameIndex * 1000000000 / 30+frameIndex*10;
        }

    }





}
