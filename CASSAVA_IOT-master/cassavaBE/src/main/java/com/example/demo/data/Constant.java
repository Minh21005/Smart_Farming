package com.example.demo.data;

public class Constant {
    public static final double latitude = 14.24; // vi do
    public static final double longitude = 102.69; // kinh do
    //14.24,102.69,295,2.5
    public static final double elevation = 295; // do cao so voi muc nuoc bien
    public static final double height = 2.5; // do cao
    public static final String USER = "user";
    public static final String TEST_USER = "testUser";
    public static final String CUSTOMIZED_PARAMETERS = "customized_parameters";
    public static final String POTENTIAL_YIELD = "potentialYield";
    public static final String POTENTIAL_YIELD_DISPLAY = "Potential Yield";
    public static final String ILA = "iLA";
    public static final String ILA_DISPLAY = "Initial leaf area";
    public static final String RGR = "rgr";
    public static final String RGR_DISPLAY = "Relative growth rate";
    public static final String AUTO_IRRIGATION = "autoIrrigation";
    public static final String AUT0_IRRIGATION_DISPLAY = "Auto irrigation";
    public static final String MEASURED_DATA = "measured_data";
    public static final String RAIN_FALL = "rainFall";
    public static final String RELATIVE_HUMIDITY = "relativeHumidity";
    public static final String TEMPERATURE = "temperature";
    public static final String SOIL_TEMPERATURE = "soilTemperature";
    public static final String WIND_SPEED = "windSpeed";
    public static final String RADIATION = "radiation";
    public static final String IRRIGATION_CHECK = "irrigationCheck";
    public static final String START_IRRIGATION = "startIrrigation";
    public static final String END_IRRIGATION = "endIrrigation";
    public static final String START_TIME = "startTime";
    public static final String FIELD_CAPACITY = "fieldCapacity";
    public static final String FIELD_CAPACITY_DISPLAY = "Field capacity to maintain (%)";
    public static final int nSoilLayer = 5;
    public static final String ACREAGE = "acreage";
    public static final String IRRIGATION_DURATION = "irrigationDuration";
    public static final String IRRIGATION_DURATION_DISPLAY = "Duration of irrigation (hour)";
    public static final String DRIP_RATE = "dripRate";
    public static final String DRIP_RATE_DISPLAY = "Drip rate of single hole (l/h/hole)";
    public static final String NUMBER_OF_HOLES = "numberOfHoles";
    public static final String NUMBER_OF_HOLES_DISPLAY = "The number of drip holes";
    public static final String DISTANCE_BETWEEN_HOLES = "distanceBetweenHole";
    public static final String DISTANCE_BETWEEN_HOLES_DISPLAY = "Distance between holes (cm)";
    public static final String DISTANCE_BETWEEN_ROWS = "distanceBetweenRow";
    public static final String DISTANCE_BETWEEN_ROWS_DISPLAY = "Distance between rows (cm)";
    public static final String SCALE_RAIN = "scaleRain";
    public static final String SCALE_RAIN_DISPLAY = "Reduce or increase expected rainfall (%)";
    public static final String FERTILIZATION_LEVEL = "fertilizationLevel";
    public static final String FERTILIZATION_LEVEL_DISPLAY = "Reduce or increase fertilizer level (%)";
    public static final String IRRIGATION_FOR_THE_NEXT_DAY = "irrigationForTheNextDay";
    public static final String IRRIGATION_INFORMATION = "irrigation_information";
    public static final String AMOUNT_OF_IRRIGATION = "amount";
    public static final String SOIL_HUMIDITY = "humidity_hour";
    public static final String IRRIGATION_HISTORY = "irrigation_history";

    public static String format(int n) {
        if (n < 10)
            return "0" + n;
        else
            return String.valueOf(n);
    }

    public Constant(){

    }
}
