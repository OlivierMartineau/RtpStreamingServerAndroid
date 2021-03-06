package com.matthijs.rtpandroid;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Permission;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * This class records and saves the camera input to a .mjpeg file
 *
 * Created by Matthijs Overboom on 1-6-16.
 */
public class VideoCaptureActivity extends Activity implements View.OnClickListener,    SurfaceHolder.Callback, Camera.PreviewCallback {
    public static final String LOGTAG = "VIDEOCAPTURE";
    String szBoundaryStart = "\r\n\r\n--myboundary\r\nContent-Type: image/jpeg\r\nContent-Length: ";
    String szBoundaryDeltaTime = "\r\nDelta-time: 110";
    String szBoundaryEnd = "\r\n\r\n";
    private SurfaceHolder holder;
    private Camera camera;
    private CamcorderProfile camcorderProfile;
    boolean bRecording = false;
    boolean bPreviewRunning = false;
    byte[] previewCallbackBuffer;
    File mjpegFile;
    FileOutputStream fos;
    BufferedOutputStream bos;
    Button btnRecord;
    Camera.Parameters p;
    private String szFileName;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String timestamp = new SimpleDateFormat("yyyyMMddhhmmss").format(new Date());
        szFileName = "videocapture-" + timestamp;
        try {
            mjpegFile = new File(Environment.getExternalStorageDirectory() + "/" + Environment.DIRECTORY_MOVIES, szFileName + ".mjpeg");
            Log.d("StreamingServer", "File path: " + mjpegFile.getAbsolutePath());
        } catch (Exception e) {
            Log.v(LOGTAG,e.getMessage());
            finish();
        }

        //Set fullscreen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.video_capture_activity);

        //Add button + listener
        btnRecord = (Button) this.findViewById(R.id.record_button);
        btnRecord.setOnClickListener(this);

        camcorderProfile = CamcorderProfile.get(CamcorderProfile.QUALITY_720P);
        SurfaceView cameraView = (SurfaceView) findViewById(R.id.camera_surface_view);
        holder = cameraView.getHolder();
        holder.addCallback(this);
    }

    /**
     * onClick listener for btnRecord
     * (Could alternatively be set on surface, i.e. the screen, to support tap-to-record)
     *
     * @param v
     */
    public void onClick(View v) {
        if (bRecording) {
            bRecording = false;
            try {
                bos.flush();
                bos.close();
                setReturnData(true);
                cleanAndClose();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Log.v(LOGTAG, "Recording Stopped");
        } else {
            try {
                fos = new FileOutputStream(mjpegFile, true);
                bos = new BufferedOutputStream(fos);
                bRecording = true;
                btnRecord.setText("Save");
                btnRecord.setBackgroundColor(getResources().getColor(R.color.colorRecord));
                Log.v(LOGTAG, "Recording Started");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }
    public void surfaceCreated(SurfaceHolder holder) {
        Log.v(LOGTAG, "surfaceCreated");
        camera = Camera.open();
    }
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.v(LOGTAG, "surfaceChanged");
        if (!bRecording) {
            if (bPreviewRunning){
                camera.stopPreview();
            } try {
                p = camera.getParameters();
                p.setPreviewSize(camcorderProfile.videoFrameWidth, camcorderProfile.videoFrameHeight);
                //p.setPreviewFrameRate(camcorderProfile.videoFrameRate);
                camera.setParameters(p);
                camera.setPreviewDisplay(holder);
                camera.setPreviewCallback(this);
                Log.v(LOGTAG,"startPreview");
                camera.startPreview();
                bPreviewRunning = true;
            } catch (IOException e) {
                Log.e(LOGTAG,e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.v(LOGTAG, "surfaceDestroyed");
        if (bRecording) {
            bRecording = false;
            try {
                bos.flush();
                bos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Close or release all resources
     * and finish the activity
     */
    private void cleanAndClose() {
        bPreviewRunning = false;
        this.holder.getSurface().release();
        camera.setPreviewCallback(null);
        holder.removeCallback(this);
        camera.release();
        finish();
    }

    /**
     * Set the return data depending on argument value
     * true -> set FILE_NAME and RESULT_OK
     * false -> set RESULT_CANCELED
     *
     * @param doSet boolean
     */
    private void setReturnData(boolean doSet) {
        Intent returnIntent = new Intent();
        if(doSet) {
            returnIntent.putExtra("FILE_NAME", szFileName);
            setResult(Activity.RESULT_OK, returnIntent);
        } else {
            setResult(Activity.RESULT_CANCELED, returnIntent);
        }
    }

    /**
     * Saves content displayed on the screen to the .mjpeg file
     * For each frame a byte[] is given and this byte[] is converted to a .jpeg image
     * This image is then written to the BufferedOutputStream as well as the frame's length
     *
     * @param b byte[] containing frame
     * @param c Camera
     */
    public void onPreviewFrame(byte[] b, Camera c) {
        if (bRecording) {
            // Assuming ImageFormat.NV21
            if (p.getPreviewFormat() == ImageFormat.NV21) {
                Log.v(LOGTAG,"Started Writing Frame");
                try {
                    //ByteArrayOutputStream to compress frame to
                    ByteArrayOutputStream jpegByteArrayOutputStream = new ByteArrayOutputStream();
                    //Set format and dimensions
                    YuvImage im = new YuvImage(b, ImageFormat.NV21, p.getPreviewSize().width, p.getPreviewSize().height, null);
                    Rect r = new Rect(0,0,p.getPreviewSize().width,p.getPreviewSize().height);
                    //Compress to jpeg and save result to jpegByteArrayOutputStream
                    im.compressToJpeg(r, 100, jpegByteArrayOutputStream);

                    //Create byte[] for temporarily save jpeg bytes
                    byte[] jpegByteArray = jpegByteArrayOutputStream.toByteArray();
                    //Set frame length along with some additional parameters
                    byte[] boundaryBytes = (szBoundaryStart + jpegByteArray.length + szBoundaryDeltaTime + szBoundaryEnd).getBytes();

                    //Write data and flush to ensure writing
                    bos.write(boundaryBytes);
                    bos.write(jpegByteArray);
                    bos.flush();

                } catch (IOException e) {
                    e.printStackTrace();
                }
                Log.v(LOGTAG,"Finished Writing Frame");
            } else {
                Log.v(LOGTAG,"NOT THE RIGHT FORMAT");
            }
        }
    }
    @Override
    public void onConfigurationChanged(Configuration conf)
    {
        super.onConfigurationChanged(conf);
    }
}
