package com.github.gotatwork_client;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.FingerprintManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.inject.Inject;

public class MainActivity extends Activity implements LocationListener {
    /* SMS */
    private static final String CUSTOM_MESSAGE = "Coordonnées GPS de : +33781705378\n\n";
    private static final String CUSTOM_SIGNATURE = "\n\nMerci d'utiliser 'GotAtWork_services' " +
            "propriété de Pierre-Alexandre ADAMSKI";
    private static final String ADMIN_PHONE_NUMBER = "+33781705378";
                                                    // "+33695689862";
    /* GPS Constant Permission */
    private static final int MY_PERMISSION_ACCESS_COARSE_LOCATION = 11;
    private static final int MY_PERMISSION_ACCESS_FINE_LOCATION = 12;

    /* Position */
    private static final int MINIMUM_TIME = 0;  // 10s
    private static final int MINIMUM_DISTANCE = 0; // 50m

    /* Fingerprint */
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final String DIALOG_FRAGMENT_TAG = "myFragment";
    private static final String SECRET_MESSAGE = "Very secret message";
    /**
     * Alias for our key in the Android Key Store
     */
    private static final String KEY_NAME = "my_key";

    @Inject
    KeyguardManager mKeyguardManager;
    @Inject
    FingerprintManager mFingerprintManager;
    @Inject
    FingerprintAuthenticationDialogFragment mFragment;
    @Inject
    KeyStore mKeyStore;
    @Inject
    KeyGenerator mKeyGenerator;
    @Inject
    Cipher mCipher;
    @Inject
    SharedPreferences mSharedPreferences;

    private Location precLoc = new Location("");
    private Location targetLoc = new Location("");

    private int changeCount = 0;
    private long delay = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((InjectedApplication) getApplication()).inject(this);

        setContentView(R.layout.activity_main);

        final CardView cardView = (CardView) findViewById(R.id.identificationCard);

        targetLoc.setLatitude(50.62708);
        targetLoc.setLongitude(3.079725);

        if (!mKeyguardManager.isKeyguardSecure()) {
            // Show a message that the user hasn't set up a fingerprint or lock screen.
            Toast.makeText(this,
                    "Secure lock screen hasn't set up.\n"
                            + "Go to 'Settings -> Security -> Fingerprint' to set up a fingerprint",
                    Toast.LENGTH_SHORT).show();
        }

        //noinspection ResourceType
        if (!mFingerprintManager.hasEnrolledFingerprints()) {
            // This happens when no fingerprints are registered.
            Toast.makeText(this,
                    "Go to 'Settings -> Security -> Fingerprint' and register at least one fingerprint",
                    Toast.LENGTH_SHORT).show();
        }

        createKey();


        cardView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Set up the crypto object for later. The object will be authenticated by use
                // of the fingerprint.
                if (initCipher()) {

                    // Show the fingerprint dialog. The user has the option to use the fingerprint with
                    // crypto, or you can fall back to using a server-side verified password.
                    mFragment.setCryptoObject(new FingerprintManager.CryptoObject(mCipher));
                    boolean useFingerprintPreference = mSharedPreferences
                            .getBoolean(getString(R.string.use_fingerprint_to_authenticate_key),
                                    true);
                    if (useFingerprintPreference) {
                        mFragment.setStage(
                                FingerprintAuthenticationDialogFragment.Stage.FINGERPRINT);
                    } else {
                        mFragment.setStage(FingerprintAuthenticationDialogFragment.Stage.PASSWORD);
                    }
                    mFragment.show(getFragmentManager(), DIALOG_FRAGMENT_TAG);
                } else {
                    // This happens if the lock screen has been disabled or or a fingerprint got
                    // enrolled. Thus show the dialog to authenticate with their password first
                    // and ask the user if they want to authenticate with fingerprints in the
                    // future
                    mFragment.setCryptoObject(new FingerprintManager.CryptoObject(mCipher));
                    mFragment.setStage(FingerprintAuthenticationDialogFragment.Stage.NEW_FINGERPRINT_ENROLLED);
                    mFragment.show(getFragmentManager(), DIALOG_FRAGMENT_TAG);
                }
            }
        });
    }

    public void gpsSetup() {
        LocationManager mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Get the best provider between gps, network and passive
        Criteria criteria = new Criteria();
        String mProviderName = mLocationManager.getBestProvider(criteria, true);

        // API 23: we have to check if ACCESS_FINE_LOCATION and/or ACCESS_COARSE_LOCATION permission are granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            // At least one provider activated. Get the coordinates
            if (mProviderName != null && !mProviderName.equals("")) {
                mLocationManager.requestLocationUpdates(mProviderName, MINIMUM_TIME, MINIMUM_DISTANCE, this);
                Location location = mLocationManager.getLastKnownLocation(mProviderName);
                onLocationChanged(location);
            }// No one provider activated: prompt GPS
            else {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                Toast.makeText(getBaseContext(), "No Provider Found", Toast.LENGTH_SHORT).show();
            }

            // One or both permissions are denied.
        } else {

            Toast.makeText(getBaseContext(), "permission request", Toast.LENGTH_SHORT).show();

            // The ACCESS_COARSE_LOCATION is denied, then I request it and manage the result in
            // onRequestPermissionsResult() using the constant MY_PERMISSION_ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                        MY_PERMISSION_ACCESS_COARSE_LOCATION);
            }
            // The ACCESS_FINE_LOCATION is denied, then I request it and manage the result in
            // onRequestPermissionsResult() using the constant MY_PERMISSION_ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        MY_PERMISSION_ACCESS_FINE_LOCATION);
            }
        }
    }

    protected void sendSMSMessage(String phoneNo, String message) {
        PendingIntent piSent = PendingIntent.getBroadcast(this, 0, new Intent("SMS_SENT"), 0);
        PendingIntent piDelivered = PendingIntent.getBroadcast(this, 0, new Intent("SMS_DELIVERED"), 0);

        SmsManager smsManager = SmsManager.getDefault();
        ArrayList<String> messageParts = smsManager.divideMessage(message);
        try {
            if (messageParts.size() > 1) {
                smsManager.sendMultipartTextMessage(phoneNo, null, messageParts, null, null);
                Toast.makeText(getApplicationContext(), "Multi part SMS SENT", Toast.LENGTH_SHORT).show();
            } else {
                smsManager.sendTextMessage(phoneNo, null, message, piSent, piDelivered);
                Toast.makeText(getApplicationContext(), "SMS SENT", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e2) {
            Toast.makeText(getApplicationContext(), "SMS FAILED", Toast.LENGTH_SHORT).show();
        }

    }

    private static final int PERMISSION_REQUEST = 100;

    //This is the onClick listener of 'Send SMS' button
    public void send(GPPSSMS message) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            //This if checks if the device is API 23 or above
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                //This checks if the permission is already granted.
                if (shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS)) {
                    //This displays a message to the user as to why do we
                    //need the permission. If the user accepts, we display the permission granting dialog.
                    Snackbar.make(findViewById(R.id.root_activity_main_layout), "You need to grant SEND SMS permission to send sms",
                            Snackbar.LENGTH_SHORT).setAction("OK", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, PERMISSION_REQUEST);
                        }
                    }).show();
                } else {
                    //This displays the permission granting dialog directly.
                    requestPermissions(new String[]{Manifest.permission.SEND_SMS}, PERMISSION_REQUEST);
                }
            } else {
                System.out.println(message.getGson());
                sendSMSMessage(ADMIN_PHONE_NUMBER, CUSTOM_MESSAGE + message.getGson() + CUSTOM_SIGNATURE);
            }
        } else {
            System.out.println(message.getGson());
            sendSMSMessage(ADMIN_PHONE_NUMBER, CUSTOM_MESSAGE + message.getGson() + CUSTOM_SIGNATURE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case MY_PERMISSION_ACCESS_COARSE_LOCATION: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Snackbar.make(findViewById(R.id.root_activity_main_layout), "Permission Granted",
                            Snackbar.LENGTH_SHORT).show();
                    return;
                } else {
                    Snackbar.make(findViewById(R.id.root_activity_main_layout), "Permission denied",
                            Snackbar.LENGTH_SHORT).show();
                }
                break;
            }
            case MY_PERMISSION_ACCESS_FINE_LOCATION: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    return;
                } else {
                    Snackbar.make(findViewById(R.id.root_activity_main_layout), "Permission denied",
                            Snackbar.LENGTH_SHORT).show();
                }
                break;
            }
            default:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Snackbar.make(findViewById(R.id.root_activity_main_layout), "Permission Granted",
                            Snackbar.LENGTH_SHORT).show();
                    return;
                    //SMS sent
                } else {
                    Snackbar.make(findViewById(R.id.root_activity_main_layout), "Permission denied",
                            Snackbar.LENGTH_SHORT).show();
                }
                break;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        // Getting reference to TextView tv_longitude
        if (precLoc.getProvider().equals("")) {
            precLoc = location;
        }


        float stepDistance = Math.abs(precLoc.distanceTo(location));
        Log.i(String.valueOf(location.getLatitude()) + "|" +
                String.valueOf(location.getLongitude()), "current LA/LON");
        Log.i(String.valueOf(stepDistance), "step distance");

        float targetDistance = Math.abs(targetLoc.distanceTo(location));
        Log.i(String.valueOf(targetLoc.getLatitude()) + "|" +
                String.valueOf(targetLoc.getLongitude()), "target LA/LON");
        Log.i(String.valueOf(targetDistance), "target distance");

        delay += Math.abs(location.getTime() - precLoc.getTime());
        if (delay > 3600 * 1000) delay = 0;

        if (stepDistance < 1000) {
            if (stepDistance < 200) {
                if (targetDistance > 500) {
                    if (targetDistance > 5000) {
                        send(new GPPSSMS(delay, location.getLongitude(), location.getLatitude(), "not_at_work"));
                    } else {
                        send(new GPPSSMS(delay, location.getLongitude(), location.getLatitude(), "out_target"));
                    }
                } else if ((targetDistance < 100 - (0.1f * stepDistance)) &&
                        (delay < 2 * 1000 || delay > 3599 * 1000)) {
                    send(new GPPSSMS(delay, location.getLongitude(), location.getLatitude(), "in_target"));
                }
                precLoc = location;
                return;
            }
        } else if ((changeCount > 5) && (delay < 60 * 5 * 1000)) {
            send(new GPPSSMS(delay, location.getLongitude(), location.getLatitude(), "wrong_data_model"));
            changeCount++;
        }
        precLoc = location;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }


    /**********************************************************************************************/
    /**********************************************************************************************/


    /**
     * Initialize the {@link Cipher} instance with the created key in the {@link #createKey()}
     * method.
     *
     * @return {@code true} if initialization is successful, {@code false} if the lock screen has
     * been disabled or reset after the key was generated, or if a fingerprint got enrolled after
     * the key was generated.
     */

    private boolean initCipher() {
        try {
            mKeyStore.load(null);
            SecretKey key = (SecretKey) mKeyStore.getKey(KEY_NAME, null);
            mCipher.init(Cipher.ENCRYPT_MODE, key);
            return true;
        } catch (KeyPermanentlyInvalidatedException e) {
            return false;
        } catch (KeyStoreException | CertificateException | UnrecoverableKeyException | IOException
                | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to init Cipher", e);
        }
    }

    public void onPurchased(boolean withFingerprint) {
        if (withFingerprint) {
            // If the user has authenticated with fingerprint, verify that using cryptography and
            // then show the confirmation message.
            tryEncrypt();
        } else {
            // Authentication happened with backup password. Just show the confirmation message.
            showConfirmation(null);
        }
    }

    // Show confirmation, if fingerprint was used show crypto information.
    private void showConfirmation(byte[] encrypted) {
        Toast.makeText(getBaseContext(), "Fingerprints accepted", Toast.LENGTH_SHORT).show();
    }

    /**
     * Tries to encrypt some data with the generated key in {@link #createKey} which is
     * only works if the user has just authenticated via fingerprint.
     */
    private void tryEncrypt() {
        try {
            byte[] encrypted = mCipher.doFinal(SECRET_MESSAGE.getBytes());
            showConfirmation(encrypted);
        } catch (BadPaddingException | IllegalBlockSizeException e) {
            Toast.makeText(this, "Failed to encrypt the data with the generated key. "
                    + "Retry the purchase", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Failed to encrypt the data with the generated key." + e.getMessage());
        }
    }

    /**
     * Creates a symmetric key in the Android Key Store which can only be used after the user has
     * authenticated with fingerprint.
     */
    public void createKey() {
        // The enrolling flow for fingerprint. This is where you ask the user to set up fingerprint
        // for your flow. Use of keys is necessary if you need to know if the set of
        // enrolled fingerprints has changed.
        try {
            mKeyStore.load(null);
            // Set the alias of the entry in Android KeyStore where the key will appear
            // and the constrains (purposes) in the constructor of the Builder
            mKeyGenerator.init(new KeyGenParameterSpec.Builder(KEY_NAME,
                    KeyProperties.PURPOSE_ENCRYPT |
                            KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                            // Require the user to authenticate with a fingerprint to authorize every use
                            // of the key
                    .setUserAuthenticationRequired(true)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build());
            mKeyGenerator.generateKey();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException
                | CertificateException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}