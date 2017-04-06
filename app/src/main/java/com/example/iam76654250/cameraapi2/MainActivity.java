package com.example.iam76654250.cameraapi2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ImageButton takePictureButton;
    private ImageButton changeCameraButton;

    private TextureView textureView;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    // Orientación de la foto para ser guardada
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    // ID de la camara (frontal o trasera)
    private String cameraId;
    // Equivalente al Camera antiguo, para procesamiento de imagenes
    protected CameraDevice cameraDevice;

    // Para capturar imagenes o reprocesar imagenes
    protected CameraCaptureSession cameraCaptureSessions;

    // Para usar en este caso el textureview y construir la imagen ahí
    protected CaptureRequest.Builder captureRequestBuilder;

    // Tamaño de la imagen
    private Size imageDimension;

    // Para acceder a los datos de la imagen capturada
    private ImageReader imageReader;

    private File file;

    // Permiso que definimos para comprobar más adelante que están habilitados
    private static final int REQUEST_CAMERA_PERMISSION = 1;

    // Id provisional de la camara
    private static int newCamera = 0;

    /**
     * Para ajustar el tamaño del textureView cuando rotamos la camara
     */
    private void configureTransform() {
        if (null == textureView || null == imageDimension || null == this) {
            return;
        }

        int rotation = this.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, textureView.getWidth(), textureView.getHeight());
        RectF bufferRect = new RectF(0, 0, imageDimension.getHeight(), imageDimension.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) textureView.getHeight() / 1 / imageDimension.getHeight(),
                    (float) textureView.getWidth() / 2 / imageDimension.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }

        if (Build.BRAND.equalsIgnoreCase("samsung")) {
            Toast.makeText(MainActivity.this, "Móvil chino Samsung detectado", Toast.LENGTH_SHORT).show();
        }

        textureView.setTransform(matrix);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = (TextureView) findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(textureListener);
        takePictureButton = (ImageButton) findViewById(R.id.fotoBtn);
        // Accion de tomar la foto
        takePictureButton.setOnClickListener((View v) -> takePicture());
        // Accion de cambiar camara
        changeCameraButton = (ImageButton) findViewById(R.id.cambiarBtn);
        changeCameraButton.setOnClickListener((View v) -> changeCamera());
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {

        // Abre la camara
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Arregla la rotacion al inicio
            configureTransform();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }

    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {

        // Se ejecuta al abrir la camara
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }

        // Cierra el CameraDevice
        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }

        // Si hay algun error cierra el CameraDevice y la pone a null
        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    protected void takePicture() {

        if (null == cameraDevice) {
            return;
        }

        // Detecta, caracteriza y conecta la camara
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {

            // Segun la camara escogida retorna las caracteristicas (resoluciones, etc)
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());

            // Array de las resoluciones de imagen disponibles
            Size[] jpegSizes = null;
            if (characteristics != null) {
                // Coge las resoluciones disponibles de la camara
                jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            }

            int width = 640;
            int height = 480;

            // Coge la primera resolución para el tamaño de la foto
            if (jpegSizes != null && 0 < jpegSizes.length) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }

            // La imagen mostrada en el TextureView
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);

            // Para poder guardar el archivo creamos un arrayList
            List<Surface> outputSurfaces = new ArrayList<Surface>();
            outputSurfaces.add(imageReader.getSurface());
            outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));

            // Crea todos los paramentros que necesita la camara para hacer la captura
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            // Orientación de la camara
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            // Archivo de salida de la imagen
            file = new File(String.format("/sdcard/%d.jpg", System.currentTimeMillis()));

            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {

                // Guarda los bytes (la imagen) antes de escribirla y los escribe con la función

                @Override
                public void onImageAvailable(ImageReader reader) {
                    try (Image image = reader.acquireLatestImage()) {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                // Escribe la imagen

                private void save(byte[] bytes) throws IOException {
                    try (OutputStream output = new FileOutputStream(file)) {
                        output.write(bytes);
                    }
                }
            };

            // Para saber si esta creada la imagen
            imageReader.setOnImageAvailableListener(readerListener, null);
            final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(getApplicationContext(), "Foto guardada en " + file, Toast.LENGTH_SHORT).show();
                    createCameraPreview();
                }
            };

            // Crea una sesion de captura de la camara
            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        session.capture(captureBuilder.build(), captureListener, null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Para cambiar de camara
    protected void changeCamera() {
        switch (newCamera) {
            case 0:
                newCamera = 1;
                break;
            case 1:
                newCamera = 0;
                break;
        }

        cameraDevice.close();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    // Vista previa de la camara
    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            // Arregla la rotacion al inicio
            configureTransform();
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    // La cámara ya está cerrada
                    if (null == cameraDevice) {
                        return;
                    }
                    // Cuando la sesión esté lista se mostrará la vista previa
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(getApplicationContext(), "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[newCamera];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // Añade  permiso para la cámara
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    protected void updatePreview() {
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Consulta los permisos
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, "Debes garantizar los permisos para usar la aplicacion", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

}