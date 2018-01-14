package com.ciklum.pigabstractionlayer.WifiProvisioning;

import android.annotation.SuppressLint;
import android.util.Log;

import com.ciklum.pigabstractionlayer.BasicAuthInterceptor;
import com.ciklum.pigabstractionlayer.HttpUtil;
import com.ciklum.pigabstractionlayer.PiggyBank;
import com.ciklum.pigabstractionlayer.PiggyBankException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;

import io.particle.android.sdk.cloud.ParticleCloudException;
import io.particle.android.sdk.cloud.ParticleCloudSDK;
import io.particle.android.sdk.cloud.Responses;
import okhttp3.Request;
import okhttp3.Response;


public class ParticleCloudAuthentication {

    private final static String BASE_URL = "https://api.particle.io";
    private final static String TAG = "ParticleCloudAuthentication";

    private static final String GRANT_TYPE = "grant_type";
    private static final String CLIENT_CREDENTIALS = "client_credentials";
    private static final String SCOPE = "scope";
    private static final String CUSTOMER = "customer=";
    private static final String NO_PASSWORD = "no_password";
    private static final String ACCESS_TOKEN = "access_token";
    private static final String EMAIL = "email"; 


    // These are the oAuth credentials that allow for us to grab an access token to the particle
    // cloud without logging in. By getting an access token, we can claim devices against the
    // inputted email address, bypassing the need for each user to create an account on the
    // particle cloud and to log in.
    private static String oAuthId = "oinkapp-5544";
    private static String oAuthSecret = "b2becc39dd7e17e1a2924142d7ff02e6b0d6d5a3";
    private static String productSlug = "oink-v100";


    //The oAuth tokens are included in the header of calls to Particle endpoitns.
    private okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
            .addInterceptor(new BasicAuthInterceptor(oAuthId, oAuthSecret))
            .build();


    //Particle Cloud Endpoints:
    private final static String CREATE_USER_ENDPOINT = BASE_URL + "/v1/products/"+ productSlug + "/customers";
    private final static String ACCESS_TOKEN_ENDPOINT = BASE_URL + "/oauth/token";


    //Expiry for the access token is set to 100 days after creation:
    private long now = new Date().getTime();
    private long hundredDaysInMs = now + (24*60*60*100);
    private Date hundredDaysFromNow = new Date(hundredDaysInMs);

    private WifiProvisionManager wifiProvisionManager;

    public ParticleCloudAuthentication(WifiProvisionManager wifiProvision){
        wifiProvisionManager = wifiProvision;
    }

    /**
     * Logs into the particle cloud using the user's access token.
     *
     *  @param userEmail
     *
     */
    @SuppressLint("LongLogTag")
    public void particleCloudLogin(String userEmail) throws PiggyBankException{
        if(PiggyBank.DEBUG) {
            Log.d("ParticleCloudLogin", "trying to log in");
        }
        try {
            wifiProvisionManager.mParticleCloud = ParticleCloudSDK.getCloud();
            getAccessTokenBlocking(userEmail);
        } catch (Exception e) {
            Log.d(TAG, "error in logging in", e);
            throw new PiggyBankException("Could not authenticate the user. " +
                    "Re-check your internet connection or the entered email address");
        }
    }

    /**
     * Attempts to get access token from the particle cloud to allow the user to login.
     * It calls /oauth/token to get that token.
     * If the user does not exist on the particle cloud, it creates an account for them using
     * two legged authentication (createUserAndGetAccessTokenBlocking)
     *
     * @param userEmail
     *
     */
    @SuppressLint("LongLogTag")
    private void getAccessTokenBlocking(final String userEmail){

        HashMap<String, String> formValues = new HashMap<String, String>();
        formValues.put(GRANT_TYPE, CLIENT_CREDENTIALS);
        formValues.put(SCOPE, CUSTOMER + userEmail);

        Request request = HttpUtil.buildPostRequest(ACCESS_TOKEN_ENDPOINT, formValues);
        Response response = null;
        try {
            response = client.newCall(request).execute();
            if(response.isSuccessful()){
                String responseString = response.body().string();
                JSONObject currentSettings = new JSONObject(responseString);
                wifiProvisionManager.mParticleAccessToken = currentSettings.getString(ACCESS_TOKEN);
                wifiProvisionManager.mParticleCloud.setAccessToken(wifiProvisionManager.mParticleAccessToken, hundredDaysFromNow);
                if(PiggyBank.DEBUG) {
                    Log.d(TAG, "The particle access token is: " + wifiProvisionManager.mParticleAccessToken);
                    Log.d(TAG, "Is loggedIn:" + wifiProvisionManager.mParticleCloud.isLoggedIn());
                }
            } else{
                createUserAndGetAccessTokenBlocking(userEmail);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }catch (JSONException e) {
            e.printStackTrace();
        }



    }

    /**
     * Gets an access token for the user email by creating an new account for them without a password
     *
     * @param userEmail
     *
     */

    private void createUserAndGetAccessTokenBlocking(String userEmail) {

        HashMap<String, String> formValues = new HashMap<String, String>();
        formValues.put(NO_PASSWORD, "true");
        formValues.put(EMAIL, userEmail);

        Request request = HttpUtil.buildPostRequest(CREATE_USER_ENDPOINT, formValues);
        Response response = null;
        try {
            response = client.newCall(request).execute();
            if(response.isSuccessful()){
                String responseString = response.body().string();
                JSONObject currentSettings = new JSONObject(responseString);
                wifiProvisionManager.mParticleAccessToken = currentSettings.getString(ACCESS_TOKEN);
                wifiProvisionManager.mParticleCloud.setAccessToken(wifiProvisionManager.mParticleAccessToken, hundredDaysFromNow);
            } else{
                wifiProvisionManager.mCallback.onFailure(new PiggyBankException("Could not complete two legged authentication"));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        }

    }


    /**
     * Generates a claim code to send to the device in the DiscoverProcessWorker process.
     *
     * @throws ParticleCloudException
     *
     */
    @SuppressLint("LongLogTag")
    public void setClaimToken(){
        try {
            Responses.ClaimCodeResponse response = wifiProvisionManager.mParticleCloud.generateClaimCode();
            wifiProvisionManager.mClaimCode = response.claimCode;
            Log.d(TAG, "Claim Code: " + wifiProvisionManager.mClaimCode);
        } catch (ParticleCloudException e) {
            e.printStackTrace();
        }
    }


}
