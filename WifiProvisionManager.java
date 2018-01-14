package com.ciklum.pigabstractionlayer.WifiProvisioning;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.ciklum.pigabstractionlayer.PigInternal;
import com.ciklum.pigabstractionlayer.PiggyBank;
import com.ciklum.pigabstractionlayer.PiggyBankException;
import com.ciklum.pigabstractionlayer.R;

import java.security.PublicKey;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.particle.android.sdk.cloud.ParticleCloud;
import io.particle.android.sdk.devicesetup.commands.CommandClient;
import io.particle.android.sdk.devicesetup.commands.ScanApCommand;
import io.particle.android.sdk.devicesetup.setupsteps.CheckIfDeviceClaimedStep;
import io.particle.android.sdk.devicesetup.setupsteps.ConfigureAPStep;
import io.particle.android.sdk.devicesetup.setupsteps.ConnectDeviceToNetworkStep;
import io.particle.android.sdk.devicesetup.setupsteps.EnsureSoftApNotVisible;
import io.particle.android.sdk.devicesetup.setupsteps.SetupStep;
import io.particle.android.sdk.devicesetup.setupsteps.SetupStepException;
import io.particle.android.sdk.devicesetup.setupsteps.StepConfig;
import io.particle.android.sdk.devicesetup.setupsteps.WaitForCloudConnectivityStep;
import io.particle.android.sdk.devicesetup.setupsteps.WaitForDisconnectionFromDeviceStep;
import io.particle.android.sdk.devicesetup.ui.SuccessActivity;
import io.particle.android.sdk.utils.SoftAPConfigRemover;

import static io.particle.android.sdk.devicesetup.ui.ConnectToApFragment.buildUnsecuredConfig;
import static io.particle.android.sdk.utils.Py.list;

/**
 *
 * 1. Two legged authentication and grabbing of claim code (particleCloudAuth) and grabs a Claim
 *    Code from the Particle Cloud
 *
 * 2. Attempts to connect to the device's SoftAP by creating a ConnectToAp object.
 *
 * 3. Connection to the device's Soft Ap retries until successful(or maxattempts reached).
 *    The callbacks (as defined in ConnectToAp.Client) are implemented in this class-
 *    onApConnectionSuccessful and onApConnectionFailure.
 *
 * 4. Once connected to the soft ap network, it attempts to get information from the device, such as the
 *    device id and the public key. It also sends the generated claim token to the device. This
 *    is done by creating DiscoverProcessWorker object.
 *
 * 5. Once information is returned, it builds a list of 6 steps to send the wifi creds to the
 *    device. It runs through these 6 steps as defined in buildSteps() using the defined AsyncTask
 *    in ConnectingProcessWorkerTask.
 *
 *
 *  All task will be launched on the background thread. All WifiProvisioningCallbacks will be launched
 *  on the UI thread.
 **/
public class WifiProvisionManager implements ConnectToAp.Client {

    private final String TAG = "WifiProvisionManager";
    private static final int MAX_RETRIES_CONFIGURE_AP = 5;
    private static final int MAX_RETRIES_CONNECT_AP = 5;
    private static final int MAX_RETRIES_DISCONNECT_FROM_DEVICE = 5;
    private static final int MAX_RETRIES_CLAIM = 5;
    private static final int MAX_NUM_DISCOVER_PROCESS_ATTEMPTS = 4;
    private static final long CONNECT_TO_DEVICE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20);
    private static final long MAX_NUM_COMMUNICATION_ATTEMPTS = 5;
    private static SoftAPConfigRemover softAPConfigRemover;

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    protected WifiProvisionCallback mCallback;
    private ParticleCloudAuthentication mParticleCloudAuthentication;
    private CommandClient mClient;
    private Context mContext;
    private String mUserEmail;
    private DiscoverProcessWorker mDiscoverProcessWorker;

    private Handler mUiThreadHandler;

    private boolean getDeviceInfoSuccess = false;
    private boolean inApConnectionSuccess = false;

    public volatile String mPreviouslyConnectedWifiNetwork;
    public volatile String mClaimCode;
    public volatile PublicKey mPublicKey;
    public volatile String mDeviceToBeSetUpId;
    public boolean mNeedToClaimDevice;

    protected int mDiscoverProcessAttempts = 0;
    protected int mGetDeviceInfoAttempts = 0;
    public ScanApCommand.Scan mNetworkToConnectTo;
    public String mNetworkSecretPlaintext;
    public String mDeviceSoftApSsid;

    protected String mParticleAccessToken;
    protected ParticleCloud mParticleCloud;



    /**
     * This starts the WifiProvisioning thread and initializes parameters for provisioning.
     *
     * @param email
     * @param context
     * @param callback
     */

    public void start(String email, Context context, WifiProvisionCallback callback) {

        // Configure the handlers.
        mHandlerThread = new HandlerThread("WifiProvision");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mUiThreadHandler = new Handler(Looper.getMainLooper());

        // Initialize fields.
        mContext = context;
        mCallback = callback;
        mUserEmail = email;

        // This call starts the actual provisioning process.
        particleCloudAuth(mUserEmail);
    }

    /**
     * Private method that stops the thread.
     */
    private void stop() {
        mHandlerThread.quitSafely();
    }

    /**
     * Called at the end of a successful provisioning process.
     */
    public void wifiProvisionSucceeded(){
        stop();
    }

    /**
     * Quits provisioning and calls the callback's failure method
     * @param exception
     */
    public void wifiProvisionFailed(final Exception exception) {
        reset();
        resetSoftAp(mContext);
        stop();
        final PiggyBankException piggyBankException = new PiggyBankException(exception.getMessage());
        mUiThreadHandler.post(new Runnable() {
            public void run() {
                mCallback.onFailure(piggyBankException);
            }
        });
    }


    /**
     * STEP 1:
     * Logs into the particle cloud and grabs a claim code for provisioning.
     *
     * @param userEmail
     */
    public void particleCloudAuth(final String userEmail){
        mHandler.post(new Runnable() {
            public void run() {
                try {
                    mParticleCloudAuthentication = new ParticleCloudAuthentication(WifiProvisionManager.this);
                    mParticleCloudAuthentication.particleCloudLogin(userEmail);
                    mParticleCloudAuthentication.setClaimToken();

                    mUiThreadHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCallback.onTwoLeggedAuth();
                        }
                    });

                    connectToSoftAp(mContext);

                } catch (Exception e) {
                    wifiProvisionFailed(e);
                }
            }
        });
    }

    /**
     * STEP 2:
     * This method attempts to connect the android device to the network broadcast by the Photon.
     * If successful,  ConnectToAp.Client.onApConnectionSuccessful is called. If not,
     * the callback, onApConnectionFailure is called.
     *
     * @param context
     */
    public void connectToSoftAp(final Context context) {
        connectToSoftApWithCallback(context, this, mHandler);
    }

    /**
     * STEP 3a:
     * Implementation of ConnectToAp.Client method.
     *
     * If the connection to Soft AP was successful, it starts a task that creates a new
     * DiscoverProcessWorker. This worker opens a socket with the Photon devices and sends it 3 codes
     * (as detailed in DiscoverProcessWorker doTheThing) to the device. If it succeeds, it moves on to send
     * wifi credentials to the device. If it fails, reaching the maxAttempts, it will fail the process.
     *
     *
     * @param config
     * @param context
     */
    @Override
    public void onApConnectionSuccessful(final WifiConfiguration config, final Context context) {

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                inApConnectionSuccess = true;
                if (PiggyBank.DEBUG) {
                    Log.d(TAG, "on ApConnectionSuccessful");
                }

                if (getDeviceInfoSuccess) {
                    return;
                }

                //if max attempts has been reached in SoftAP, then provisioning fails
                if (!canConnectToDeviceAgain() && !getDeviceInfoSuccess) {
                    wifiProvisionFailed(new PiggyBankException("Max Attempts to connect to SoftAP has been reached"));
                    return;
                }


                // This ensures that the SSID that we are currently connected to is in fact the photon's
                // Soft AP's SSID. At times, ConnectToAp would call onApConnectionSuccessful when in
                // fact the application/mobile device was still connecting to the Photon's Soft Ap.
                // This checks to make sure that a connection has been made.
                String ssid = config.SSID;
                String quotesSSID = "\"" + mDeviceSoftApSsid + "\"";

                if (!ssid.equals(quotesSSID)) {
                    Log.d(TAG, "Not connected to: " + mDeviceSoftApSsid + " Connected to " + ssid);
                    if (canStartProcessAgain()) {
                        connectToSoftAp(context);
                        return;
                    } else {
                        wifiProvisionFailed(new PiggyBankException("Not connected to the device's SSID anymore"));
                        return;
                    }
                }


                // Anytime onApConnectionSuccess is called, we increase the attempts to reach the device
                // onApConnectionSuccess is called recursively until a successful connection is reached
                // or once the max tries is reached (5).
                mGetDeviceInfoAttempts++;

                resetWorker();

                try {
                    if (PiggyBank.DEBUG) {
                        Log.d(TAG, "Waiting a couple seconds before trying the socket connection...");
                    }

                    mUiThreadHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCallback.onSoftApConnected();
                        }
                    });

                    mDiscoverProcessWorker.doTheThing(
                            new InterfaceBindingSocketFactoryDev(context, mDeviceSoftApSsid));

                    inApConnectionSuccess = false;
                    getDeviceInfoSuccess = true;

                    mUiThreadHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCallback.onDeviceInfoReturned();
                        }
                    });

                    connectDeviceToNetwork(context);

                } catch (SetupStepException e) {

                    // This structure has been inherited from Photon. If "doTheThing" fails, or if
                    // the app is unable to write/recieve commands over the open socket with the
                    // device, an exception is thrown and caught here. The app will retry to open
                    // a socket with the device by called onApConnectionSuccessful again until
                    // max attempts is reached (5).
                    Log.d(TAG, "Setup exception thrown: ", e);
                    inApConnectionSuccess = false;
                    onApConnectionSuccessful(config, context);
                }

            }
        }, 3000);

    }

    /**
     * STEP 3b:
     *
     * Implementation of ConnectToAp.Client method.
     *
     * If the app was unable to connect to the device's soft ap network, it will retry. However,
     * it will fail the entire process if the MaxAttempts (5x) is reached.
     *
     * @param config
     * @param context
     *
     */
    @Override
    public void onApConnectionFailed(WifiConfiguration config, Context context) {

        Log.d(TAG, "on ApConnectionFailure");


        if (canStartProcessAgain() && !inApConnectionSuccess && !getDeviceInfoSuccess) {
            wifiProvisionFailed(new PiggyBankException("Max Attempts Reached - Cannot Connect to the Device SSID"));
        } else  {
            // If connection fails, the app retries to make a connection with the Photon until maxAttempts
            // is reached.
           connectToSoftAp(context);
        }

    }
    /**
     * Step 4:
     * Starts the "connection activity" to connect the device to the network where it
     * runs through the list of steps returned by buildSteps
     *
     * @param context
     *
     */
    public void connectDeviceToNetwork(final Context context){
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "About to connect the Device to the Network");
                startConnectingActivity(context);
            }
        });
    }


    // UTIL METHODS:

    private void resetWorker() {
        mClient = CommandClient.newClientUsingDefaultSocketAddress();
        mDiscoverProcessWorker = new DiscoverProcessWorker(mClient, this);
    }

    /**
     * Starts the "connection activity" where it runs through the list of steps returned by
     * buildSteps
     *
     * @param context
     *
     */
    private void startConnectingActivity(Context context){
        ConnectingProcessWorkerTask connectingProcessWorkerTask = new ConnectingProcessWorkerTask(buildSteps(context),
                                                                                        15, this, mContext);
        connectingProcessWorkerTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);

    }

    /**
     *
     * This returns a list of steps that the "ConnectingProcessWorkerTask" runs through.
     *
     * Step 1: Sends the wifi credentials to the device.
     * Step 2: Sends a command to the device to connect to the network
     * Step 3: Waits for a disconnection from the device.
     * Step 4: Ensures that Soft AP is no longer visible
     * Step 5: Makes sure that the device able to reach the particle cloud
     * Step 6: Makes sure that the device is claimed.
     *
     * @param context
     * @return
     */
    private List<SetupStep> buildSteps(Context context){

        ConfigureAPStep configureAPStep = new ConfigureAPStep(
                StepConfig.newBuilder()
                        .setMaxAttempts(MAX_RETRIES_CONFIGURE_AP)
                        .setResultCode(SuccessActivity.RESULT_FAILURE_CONFIGURE)
                        .setStepId(R.id.configure_device_wifi_credentials)
                        .build(),
                mClient, mNetworkToConnectTo, mNetworkSecretPlaintext, mPublicKey, context);

        ConnectDeviceToNetworkStep connectDeviceToNetworkStep = new ConnectDeviceToNetworkStep(
                StepConfig.newBuilder()
                        .setMaxAttempts(MAX_RETRIES_CONNECT_AP)
                        .setResultCode(SuccessActivity.RESULT_FAILURE_CONFIGURE)
                        .setStepId(R.id.connect_to_wifi_network)
                        .build(),
                mClient, context);

        WaitForDisconnectionFromDeviceStep waitForDisconnectionFromDeviceStep = new WaitForDisconnectionFromDeviceStep(
                StepConfig.newBuilder()
                        .setMaxAttempts(MAX_RETRIES_DISCONNECT_FROM_DEVICE)
                        .setResultCode(SuccessActivity.RESULT_FAILURE_NO_DISCONNECT)
                        .setStepId(R.id.connect_to_wifi_network)
                        .build(),
                mDeviceSoftApSsid, context);

        EnsureSoftApNotVisible ensureSoftApNotVisible = new EnsureSoftApNotVisible(
                StepConfig.newBuilder()
                        .setMaxAttempts(MAX_RETRIES_DISCONNECT_FROM_DEVICE)
                        .setResultCode(SuccessActivity.RESULT_FAILURE_CONFIGURE)
                        .setStepId(R.id.wait_for_device_cloud_connection)
                        .build(),
                mDeviceSoftApSsid, context);

        WaitForCloudConnectivityStep waitForLocalCloudConnectivityStep = new WaitForCloudConnectivityStep(
                StepConfig.newBuilder()
                        .setMaxAttempts(MAX_RETRIES_DISCONNECT_FROM_DEVICE)
                        .setResultCode(SuccessActivity.RESULT_FAILURE_NO_DISCONNECT)
                        .setStepId(R.id.check_for_internet_connectivity)
                        .build(),
                mParticleCloud, context);

        CheckIfDeviceClaimedStep checkIfDeviceClaimedStep = new CheckIfDeviceClaimedStep(
                StepConfig.newBuilder()
                        .setMaxAttempts(MAX_RETRIES_CLAIM)
                        .setResultCode(SuccessActivity.RESULT_FAILURE_CLAIMING)
                        .setStepId(R.id.verify_product_ownership)
                        .build(),
                mParticleCloud, mDeviceToBeSetUpId, mNeedToClaimDevice);

        return list(
                configureAPStep,
                connectDeviceToNetworkStep,
                waitForDisconnectionFromDeviceStep,
                ensureSoftApNotVisible,
                waitForLocalCloudConnectivityStep,
                checkIfDeviceClaimedStep
        );

    }


    protected boolean canStartProcessAgain() {
        return mDiscoverProcessAttempts < MAX_NUM_DISCOVER_PROCESS_ATTEMPTS;
    }

    protected boolean canConnectToDeviceAgain(){
        return mGetDeviceInfoAttempts < MAX_NUM_COMMUNICATION_ATTEMPTS;
    }


    public void connectToSoftApWithCallback(Context context, ConnectToAp.Client callback, Handler handler) {
        mDiscoverProcessAttempts++;
        WifiConfiguration wifiConfig = buildUnsecuredConfig(
                mDeviceSoftApSsid, false);
        softAPConfigRemover.onSoftApConfigured(wifiConfig.SSID);
        ConnectToAp connection = new ConnectToAp(context, callback, handler);
        connection.connectToAP(wifiConfig, CONNECT_TO_DEVICE_TIMEOUT_MILLIS, context);
    }

    /**
     * Reverses the effects to trying to connect to the Soft AP network (re-enables the existing
     * network, etc)
     */
    public static void resetSoftAp(Context context){
        softAPConfigRemover = new SoftAPConfigRemover(context);
        softAPConfigRemover.removeAllSoftApConfigs();
        softAPConfigRemover.reenableWifiNetworks();
    }

    public void reset(){
        mClaimCode = null;
        mPublicKey = null;
        mDeviceToBeSetUpId = null;
        mPreviouslyConnectedWifiNetwork = null;
        mDiscoverProcessAttempts = 0;
        mGetDeviceInfoAttempts = 0;
    }

    /**
     * Creates a piggybank object at the end of WifiProvisioning based on the information learnt
     * through wifi provisioning.
     *
     * @return PiggyBank
     */
    public PiggyBank createWifiProvisionPiggybank(){
        PigInternal pigInternal = new PigInternal(mDeviceToBeSetUpId);
        pigInternal.setDevicePlatformAccessToken(mParticleAccessToken);
        PiggyBank piggyBank = new PiggyBank();
        piggyBank.mPigInternal = pigInternal;
        return piggyBank;
    }
}