package com.github.gotatwork_client;

import com.google.gson.Gson;

/**
 * FingerprintDialog : created by mb-p_pilou on 13/04/16.
 */
public class GPPSSMS {

    float time;
    double longitude;
    double latitude;
    String comment;

    public GPPSSMS(float time, double longitude, double latitude, String comment) {
        this.comment = comment;
        this.latitude = latitude;
        this.longitude = longitude;
        this.time = time;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public float getTime() {
        return time;
    }

    public void setTime(float time) {
        this.time = time;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getGson(){
        final Gson gson = new Gson();
        return gson.toJson(this);
    }
}
