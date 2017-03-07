package com.nhancv.financechart;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import com.nhancv.financechart.lib.FinanceChart;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FinanceChart financeChart = (FinanceChart) findViewById(R.id.financeChart);




    }
}
