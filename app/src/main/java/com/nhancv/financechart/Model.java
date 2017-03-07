package com.nhancv.financechart;

/**
 * Created by nhancao on 3/7/17.
 */

public class Model {
    private String title;
    private String value;
    private float yVal;
    private float xVal;

    public Model(String title, String value) {
        this.title = title;
        this.value = value;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public float getYVal() {
        return yVal;
    }

    public void setYVal(float yVal) {
        this.yVal = yVal;
    }

    public float getXVal() {
        return xVal;
    }

    public void setXVal(float xVal) {
        this.xVal = xVal;
    }
}
