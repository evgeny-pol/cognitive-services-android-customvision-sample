/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package demo.tensorflow.org.customvision_sample;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Trace;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.microsoft.codepush.common.core.CodePushAPI;
import com.microsoft.codepush.common.datacontracts.CodePushRemotePackage;
import com.microsoft.codepush.common.datacontracts.CodePushSyncOptions;
import com.microsoft.codepush.common.enums.CodePushInstallMode;
import com.microsoft.codepush.common.enums.CodePushSyncStatus;
import com.microsoft.codepush.common.exceptions.CodePushNativeApiCallException;
import com.microsoft.codepush.common.interfaces.CodePushSyncStatusListener;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import demo.tensorflow.org.customvision_sample.connection.AzureUploader;
import demo.tensorflow.org.customvision_sample.connection.RemoteRecognizer;
import demo.tensorflow.org.customvision_sample.env.ImageUtils;
import demo.tensorflow.org.customvision_sample.env.Logger;

public abstract class CameraActivity extends Activity implements OnImageAvailableListener, Camera.
        PreviewCallback, CodePushSyncStatusListener {
    private static final Logger LOGGER = new Logger();

    private static final int PERMISSIONS_REQUEST = 1;

    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

    private boolean debug = false;

    private boolean useCamera2API;
    protected Bitmap rgbFrameBitmap = null;
    private int[] rgbBytes = null;
    protected int previewWidth = 0;
    protected int previewHeight = 0;
    protected Bitmap croppedBitmap = null;
    protected static final boolean SAVE_PREVIEW_BITMAP = false;
    protected long lastProcessingTimeMs;
    protected Bitmap cropCopyBitmap;
    protected ResultsView resultsView;
    protected boolean computing = false;
    protected Runnable postInferenceCallback;
    protected byte[][] yuvBytes = new byte[3][];
    protected int yRowStride;

    private CodePushAPI codePush;
    private ImageButton syncButton;
    private ImageButton addButton;
    private ProgressBar progressBar;
    private ProgressBar progressBar2;
    private boolean syncInProgress;
    private String mLabel;
    private boolean noModel = true;
    private Fragment fragment;
    private boolean mTrainingInProgress;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        LOGGER.d("onCreate " + this);
        super.onCreate(null);
        //getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_camera);
        syncButton = findViewById(R.id.sync_button);
        addButton = findViewById(R.id.add_button);
        progressBar = findViewById(R.id.progress_bar);
        progressBar2 = findViewById(R.id.progress_bar2);
        syncButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                onSync();
            }
        });
        final View dialogView = getLayoutInflater().inflate(R.layout.edit_text, null);
        final EditText editText = dialogView.findViewById(R.id.edit_text);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                AlertDialog editDialog = new AlertDialog.Builder(CameraActivity.this)
                        .setTitle("Enter name")
                        .setView(dialogView)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialogInterface, int i) {
                                mTrainingInProgress = true;
                                mLabel = editText.getText().toString();
                                addPerson();
                            }
                        })
                        .create();
                editDialog.show();
            }
        });

        if (hasPermission()) {
            setFragment();
        } else {
            requestPermission();
        }
        codePush = CodePushAPI.builder("4VnyrkITHiZ6Qroh19nsQkebgfZLSyNJucKym", getApplication(), "8fc35262-2bfb-46d7-b1c6-c9a32dd9ca7a").setAppEntryPoint("codepush_update").build();
        codePush.addSyncStatusListener(this);
        sync();
        checkModel();
    }

    private void checkModel() {
        try {
            if (codePush.getPackageFolderPath() != null) {
                MSCognitiveServicesClassifier.MODEL_FILE = codePush.getPackageFolderPath()
                        + File.separator
                        + "dynamic_mtcnn.pb";
            }
        } catch (Exception e) {
        }
        String model = MSCognitiveServicesClassifier.MODEL_FILE;
        boolean isAsset = model.startsWith("file:///android_asset/");
        if (isAsset) {
            String modelName = model.split("file:///android_asset/")[1];
            try {
                noModel = false;
                getAssets().open(modelName);
            } catch (Exception e) {
                noModel = true;
            }
        } else {
            File modelFile = new File(model);
            noModel = !modelFile.exists();
        }
    }

    private void sync() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    CodePushRemotePackage updatePackage = codePush.checkForUpdate();
                    if (updatePackage != null) {
                        updateSyncButton(true);
                    }
                } catch (CodePushNativeApiCallException e) {
                    Log.e("CODEPUSH", e.getMessage());
                }
            }
        }).start();
    }

    private void updateSyncButton(final boolean updateAvailable) {
        runOnUiThread(new Runnable() {
            @Override public void run() {

                Drawable drawable = getApplicationContext().getResources().getDrawable(updateAvailable ? R.drawable.ic_arrow_down_white_48dp : R.drawable.baseline_cached_white_48);
                //img.setBounds( 0, 0, 60, 60 );
                syncButton.setImageDrawable(drawable);
            }
        });

    }

    public void onSync() {
        syncInProgress = true;
        final CodePushSyncOptions codePushSyncOptions = new CodePushSyncOptions();
        codePushSyncOptions.setInstallMode(CodePushInstallMode.IMMEDIATE);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    codePush.sync(codePushSyncOptions);
                } catch (CodePushNativeApiCallException e) {
                    Log.e("CODEPUSH", e.getMessage());
                }
            }
        }).start();
    }

    public void syncStatusChanged(final CodePushSyncStatus syncStatus) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                switch (syncStatus) {
                    case INSTALLING_UPDATE:
                    case CHECKING_FOR_UPDATE:
                    case DOWNLOADING_PACKAGE:
                    case SYNC_IN_PROGRESS: {
                        syncButton.setVisibility(View.GONE);
                        progressBar.animate();
                        progressBar.setVisibility(View.VISIBLE);
                    }
                    break;
                    case UPDATE_INSTALLED:
                        checkModel();
                    default: {
                        syncButton.setVisibility(View.VISIBLE);
                        progressBar.setVisibility(View.GONE);
                        syncInProgress = false;
                        updateSyncButton(false);
                    }
                }
            }
        });
    }

    /**
     * Callback for android.hardware.Camera API
     */
    @Override
    public void onPreviewFrame(final byte[] bytes, final Camera camera) {
        if (computing || syncInProgress || noModel) {
            return;
        }
        computing = true;
        yuvBytes[0] = bytes;
        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                Camera.Size previewSize = camera.getParameters().getPreviewSize();
                previewHeight = previewSize.height;
                previewWidth = previewSize.width;
                rgbBytes = new int[previewWidth * previewHeight];
                onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
            }
            //ImageUtils.convertYUV420SPToARGB8888(bytes, rgbBytes, previewWidth, previewHeight, false);
            ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
        } catch (final Exception e) {
            LOGGER.e(e, "Exception!");
            return;
        }
        postInferenceCallback = new Runnable() {
            @Override
            public void run() {
                camera.addCallbackBuffer(bytes);
            }
        };
        processImageRGBbytes(rgbBytes);
    }

    /**
     * Callback for Camera2 API
     */
    @Override
    public void onImageAvailable(final ImageReader reader) {
        Image image = null;
        //We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return;
        }
        rgbBytes = new int[previewWidth * previewHeight];
        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            if (computing || syncInProgress || noModel) {
                image.close();
                return;
            }
            computing = true;
            Trace.beginSection("imageAvailable");
            final Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes[0],
                    yuvBytes[1],
                    yuvBytes[2],
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgbBytes);
            image.close();

        } catch (final Exception e) {
            if (image != null) {
                image.close();
            }
            LOGGER.e(e, "Exception!");
            Trace.endSection();
            return;
        }
        processImageRGBbytes(rgbBytes);
        Trace.endSection();
    }

    @Override
    public synchronized void onStart() {
        LOGGER.d("onStart " + this);
        super.onStart();
    }

    @Override
    public synchronized void onResume() {
        LOGGER.d("onResume " + this);
        super.onResume();
    }

    private void addPerson() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.setType("image/*");
        startActivityForResult(intent, 123);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mTrainingInProgress = false;
        if (resultCode == RESULT_OK && requestCode == 123) {
            addButton.setVisibility(View.GONE);
            progressBar2.animate();
            progressBar2.setVisibility(View.VISIBLE);

            ClipData clipData = data.getClipData();
            final ArrayList<Uri> uris = new ArrayList<>();

            if (clipData == null) {
                uris.add(data.getData());
            } else {
                for (int i = 0; i < clipData.getItemCount(); i++)
                    uris.add(clipData.getItemAt(i).getUri());
            }

            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        updateData(mLabel, getContentResolver(), uris);
                    } catch (Exception e) {
                        LOGGER.e(e, "Exception!");
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            addButton.setVisibility(View.GONE);
                            progressBar2.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }).start();
        }
    }

    void updateData(String label, ContentResolver contentResolver, ArrayList<Uri> uris) throws Exception {
        synchronized (this) {
            AzureUploader azureUploader = new AzureUploader(getApplicationContext());
            ArrayList<String> azureUris = azureUploader.sendTrainingImages(contentResolver, label, uris);
            RemoteRecognizer remoteRecognizer = new RemoteRecognizer(azureUris, label);
           /* remoteRecognizer.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            remoteRecognizer.get();*/
        }
    }

    @Override
    public synchronized void onPause() {
        LOGGER.d("onPause " + this);

        if (!isFinishing() && !mTrainingInProgress && !syncInProgress) {
            LOGGER.d("Requesting finish");
            finish();
        }

        super.onPause();
    }

    @Override
    public synchronized void onStop() {
        LOGGER.d("onStop " + this);
        super.onStop();
    }

    @Override
    public synchronized void onDestroy() {
        LOGGER.d("onDestroy " + this);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode, final String[] permissions, final int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED
                        && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    setFragment();
                } else {
                    requestPermission();
                }
            }
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) ||
                    shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
                Toast.makeText(CameraActivity.this,
                        "Camera AND storage permission are required for this demo", Toast.LENGTH_LONG).show();
            }
            requestPermissions(new String[]{PERMISSION_CAMERA, PERMISSION_STORAGE}, PERMISSIONS_REQUEST);
        }
    }

    // Returns true if the device supports the required hardware level, or better.
    boolean isHardwareLevelSupported(CameraCharacteristics characteristics, int requiredLevel) {
        int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return requiredLevel == deviceLevel;
        }
        // deviceLevel is not LEGACY, can use numerical sort
        return requiredLevel <= deviceLevel;
    }

    private String chooseCamera() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                final StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }

                useCamera2API = isHardwareLevelSupported(characteristics,
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                LOGGER.i("Camera API lv2?: %s", useCamera2API);
                return cameraId;
            }
        } catch (CameraAccessException e) {
            LOGGER.e(e, "Not allowed to access camera");
        }

        return null;
    }

    protected void setFragment() {
        String cameraId = chooseCamera();
        if (useCamera2API) {
            CameraConnectionFragment camera2Fragment =
                    CameraConnectionFragment.newInstance(
                            new CameraConnectionFragment.ConnectionCallback() {
                                @Override
                                public void onPreviewSizeChosen(final Size size, final int rotation) {
                                    previewHeight = size.getHeight();
                                    previewWidth = size.getWidth();
                                    CameraActivity.this.onPreviewSizeChosen(size, rotation);
                                }
                            },
                            this,
                            getLayoutId(),
                            getDesiredPreviewFrameSize());

            camera2Fragment.setCamera(cameraId);
            fragment = camera2Fragment;
        } else {
            fragment = new LegacyCameraConnectionFragment(this, getLayoutId());
        }

        getFragmentManager()
                .beginTransaction()
                .replace(R.id.container, fragment)
                .commit();
    }

    protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    public boolean isDebug() {
        return debug;
    }

    public void requestRender() {
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.postInvalidate();
        }
    }

    public void addCallback(final OverlayView.DrawCallback callback) {
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.addCallback(callback);
        }
    }

    public void onSetDebug(final boolean debug) {
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            debug = !debug;
            requestRender();
            onSetDebug(debug);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    protected abstract void processImageRGBbytes(int[] rgbBytes);

    protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

    protected abstract int getLayoutId();

    protected abstract Size getDesiredPreviewFrameSize();
}
