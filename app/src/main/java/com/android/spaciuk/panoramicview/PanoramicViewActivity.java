package com.android.spaciuk.panoramicview;

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;

import com.google.vr.sdk.widgets.common.VrWidgetView;
import com.google.vr.sdk.widgets.pano.VrPanoramaEventListener;
import com.google.vr.sdk.widgets.pano.VrPanoramaView.Options;
import com.google.vr.sdk.widgets.pano.VrPanoramaView;

import java.io.IOException;
import java.io.InputStream;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class PanoramicViewActivity extends Activity {

    private static final String TAG = PanoramicViewActivity.class.getSimpleName();
    /** Actual panorama widget. **/
    private VrPanoramaView panoWidgetView;
    /** Tracks the file to be loaded across the lifetime of this app. **/
    private Uri fileUri = null;
    /** Configuration information for the panorama. **/
    private Options panoOptions = new Options();
    private ImageLoaderTask backgroundImageLoaderTask;
    private String nextImage = null;
    /* MQTT variables */
    private MqttAndroidClient mqttAndroidClient;
    private String subscriptionTopic = null;
    private String serverUri = null;
    private String clientId = null;
    private String first_message_part = null;
    private String mqtt_user = null;
    private String mqtt_password = null;
    /**
     * Called when the app is launched via the app icon or an intent using the adb command above. This
     * initializes the app and loads the image to render.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeActivity();

        initializeMQTT();
    }

    @Override
    protected void onPause() {
        panoWidgetView.pauseRendering();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        panoWidgetView.resumeRendering();
    }

    @Override
    protected void onDestroy() {
        // Destroy the widget and free memory.
        panoWidgetView.shutdown();

        // The background task has a 5 second timeout so it can potentially stay alive for 5 seconds
        // after the activity is destroyed unless it is explicitly cancelled.
        if (backgroundImageLoaderTask != null) {
            backgroundImageLoaderTask.cancel(true);
        }
        super.onDestroy();
    }

    protected void initializeActivity(){
        setContentView(R.layout.activity_panoramic_view);
        panoOptions.inputType = Options.TYPE_STEREO_OVER_UNDER;

        subscriptionTopic = getString(R.string.topic_name);
        serverUri = getString(R.string.mqtt_server_url);
        clientId = getString(R.string.client_id);
        first_message_part = getString(R.string.first_message_part);
        mqtt_user = getString(R.string.mqtt_user);
        mqtt_password = getString(R.string.mqtt_password);

        panoWidgetView = (VrPanoramaView) findViewById(R.id.pano_view);

        panoWidgetView.setInfoButtonEnabled(false);
        panoWidgetView.setFullscreenButtonEnabled(false);
        panoWidgetView.setStereoModeButtonEnabled(false);
        panoWidgetView.setDisplayMode(VrWidgetView.DisplayMode.FULLSCREEN_STEREO);

        panoWidgetView.setEventListener(new VrPanoramaEventListener());
    }

    protected void changeImage(String imageName){
        nextImage = imageName;
        Log.i(TAG, "New Image: " + nextImage);
        // Load the bitmap in a background thread to avoid blocking the UI thread. This operation can
        // take 100s of milliseconds.
        if (backgroundImageLoaderTask != null) {
            // Cancel any task from a previous intent sent to this activity.
            backgroundImageLoaderTask.cancel(true);
        }
        backgroundImageLoaderTask = new ImageLoaderTask();
        backgroundImageLoaderTask.execute(Pair.create(fileUri, panoOptions));
    }

    protected void initializeMQTT(){

        mqttAndroidClient = new MqttAndroidClient(getApplicationContext(),serverUri,clientId);
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                if (reconnect) {
                    Log.i(TAG,"Reconnected to : " + serverURI);
                    // Because Clean Session is true, we need to re-subscribe
                    subscribeToTopic();
                } else {
                    Log.i(TAG,"Connected to: " + serverURI);
                }
            }
            @Override
            public void connectionLost(Throwable cause) {
                Log.i(TAG,"The Connection was lost.");
            }
            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                String message_payload = new String(message.getPayload());
                Log.i(TAG,"Incoming message: " + message_payload);
                if(message_payload.contains(first_message_part)){
                    String[] split = message_payload.split(first_message_part);
                    changeImage(split[1].trim()); // Look ! It must pass the name of the file only.
                }
            }
            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });

        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);
        mqttConnectOptions.setCleanSession(true);
        mqttConnectOptions.setUserName(mqtt_user);
        mqttConnectOptions.setPassword(mqtt_password.toCharArray());

        try {
            Log.i(TAG,"Connecting to " + serverUri);
            mqttAndroidClient.connect(mqttConnectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                    disconnectedBufferOptions.setBufferEnabled(true);
                    disconnectedBufferOptions.setBufferSize(100);
                    disconnectedBufferOptions.setPersistBuffer(false);
                    disconnectedBufferOptions.setDeleteOldestMessages(false);
                    mqttAndroidClient.setBufferOpts(disconnectedBufferOptions);
                    subscribeToTopic();
                }
                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG,"Failed to connect to: " + serverUri);
                    Log.e(TAG, exception.getMessage());
                }
            });
        } catch (MqttException ex){
            ex.printStackTrace();
        }
    }

    protected void subscribeToTopic(){
        try {
            mqttAndroidClient.subscribe(subscriptionTopic, 0, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.i(TAG,"Subscribed!");
                }
                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.i(TAG,"Failed to subscribe");
                }
            });
        } catch (MqttException ex){
            Log.e(TAG,"Exception whilst subscribing");
            ex.printStackTrace();
        }
    }

    /**
     * Helper class to manage threading.
     */
    class ImageLoaderTask extends AsyncTask<Pair<Uri, Options>, Void, Boolean> {
        /**
         * Reads the bitmap from disk in the background and waits until it's loaded by pano widget.
         */
        @Override
        protected Boolean doInBackground(Pair<Uri, Options>... fileInformation) {
            InputStream istr = null;
            AssetManager assetManager = getAssets();
            try {
                istr = assetManager.open(nextImage);
            } catch (IOException e) {
                Log.e(TAG, "Could not decode default bitmap: " + e);
                return false;
            }

            panoWidgetView.loadImageFromBitmap(BitmapFactory.decodeStream(istr), panoOptions);
            try {
                istr.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close input stream: " + e);
            }

            return true;
        }
    }

}
