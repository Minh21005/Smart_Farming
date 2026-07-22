package com.example.demo.entity;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;

import static java.lang.Math.*;

public class Field {

    public static final double _APPi = 1.00 * 1.00; // Area per plant (row x interRow spacing) (m2)
    public static final int _nsl = 5; // number of soil layer
    public static final double _lw = 0.9 / _nsl; // depth/_nsl // thickness of a layer (m) _depth = 0.9
    public static final double _lvol = _lw * _APPi; // depth*_APPI/_nsl // volume of one soil layer
    public static final double _BD = 1360; // soild bulk density in (kg/m3) # Burrium 1.36, Ratchaburi 1.07 g.cm3
    public static double _cuttingDryMass = 75.4; // g
    public static double _leafAge = 75;
    public static double _SRL = 39.0; // m/g
    public static double _iStart = 91;
    public static double _iend = 361;
    public static boolean _zerodrain = true;
    public static double _iTheta = 0.2;
    public static double _thm = 0.18; // drier
    public static double _ths = 0.43; // field capacity, not saturation
    public static double _thr = 0.065; // residual water content
    public static double _thg = 0.02;
    public static double _rateFlow = 1.3;

    final int _iDOY = 1;

    // Concurrent-safe weather data (read by getWeatherData, written by loadAllWeatherDataFromMongo).
    public static List<List<Object>> _weatherData = new java.util.concurrent.CopyOnWriteArrayList<>();

    public List<HistoryIrrigation> listHistory = new ArrayList<>();

    public double _fcthresold;
    public double _IrrigationRate;
    public double _autoIrrigationDuration;

    // Flat params (matching MongoEntity/Field)
    public double acreage;
    public double fieldCapacity;
    public double distanceBetweenRow;
    public double distanceBetweenHole;
    public double dripRate;
    public boolean autoIrrigation;
    public int numberOfHoles;
    public double fertilizationLevel;
    public double irrigationDuration;
    public double scaleRain;

    double _autoIrrigateTime = -1;
    public List<List<Double>> _results = new ArrayList<>();

    public Field(String name) {
        this.acreage = 50;
        this.fieldCapacity = 60;
        this.distanceBetweenHole = 30;
        this.irrigationDuration = 2;
        this.distanceBetweenRow = 100;
        this.dripRate = 1.6;
        this.fertilizationLevel = 100;
        this.scaleRain = 100;
        this.numberOfHoles = 8;
        this.autoIrrigation = true;
    }

    public static double relTheta(double th) {
        return lim((th - _thr) / (_ths - _thr), 0, 1);
    }

    public static double lim(double x, double xl, double xu) {
        if (x > xu) {
            return xu;
        } else if (x < xl) {
            return xl;
        } else {
            return x;
        }
    }

    // Static accumulator: tracks the previous DOY across calls so that wrapping past
    // year-end (Dec 31 → Jan 1) advances by 365 instead of jumping backward. Reset
    // via resetDoyStaticFields() before each fresh weather-data load.
    private static double previousDoy = -1;
    private static double doyOffset = 0;

    public static double getDoy(Date sd) {
        Calendar rsd = Calendar.getInstance();
        rsd.setTime(sd);
        rsd.set(Calendar.MONTH, Calendar.JANUARY);
        rsd.set(Calendar.DAY_OF_MONTH, 1);
        rsd.set(Calendar.HOUR_OF_DAY, 0);
        rsd.set(Calendar.MINUTE, 0);
        rsd.set(Calendar.SECOND, 0);

        double doy = (double) ((sd.getTime() - rsd.getTime().getTime()) / (1000 * 60 * 60 * 24));
        doy += sd.getHours() / 24.0 +
                sd.getMinutes() / (24.0 * 60.0) +
                sd.getSeconds() / (24.0 * 60.0 * 60.0);

        if (previousDoy >= 0 && doy < previousDoy) {
            doyOffset += 365;
        }

        previousDoy = doy;

        return doy + doyOffset;
    }

    public static List<Double> multiplyListsWithConstant(List<Double> l, double c) {
        List<Double> result = new ArrayList<>();
        for (Double number : l) {
            result.add(number * c);
        }
        return result;
    }

    public static double monod(double conc, double Imax, double Km) {
        double pc = Math.max(0.0, conc);
        return pc * Imax / (Km + pc);
    }

    public static double logistic(double x, double x0, double xc, double k, double m) {
        return x0 + (m - x0) / (1 + exp(-k * (x - xc)));
    }

    public static double photoFixMean(double ppfd, double lai,
                                      double kdf, double Pn_max, double phi, double k) {
        double r = 0;
        int n = 30;
        double b = 4 * k * Pn_max;
        for (int i = 0; i < n; ++i) {
            double kf = exp(kdf * lai * (i + 0.5) / n);
            double I = ppfd * kf;
            double x0 = phi * I;
            double x1 = x0 + Pn_max;
            double p = x1 - sqrt(x1 * x1 - b * x0);
            r += p;
        }
        r *= -12e-6 * 60 * 60 * 24 * kdf * _APPi * lai / n / (2 * k);
        return r;
    }

    public static double fSLA(double ct) {
        return logistic(ct, 0.04, 60, 0.1, 0.0264);
    }

    public static double fKroot(double th, double rl) {
        double rth = relTheta(th);
        double kadj = min(1.0, pow(rth / 0.4, 1.5));
        double Ksr = 0.01;
        return Ksr * kadj * rl;
    }

    public static double getStress(double clab, double dm, double low, double high, boolean swap) {
        if (high < -9999.0) {
            high = low + 0.01;
        }
        double dm1 = Math.max(dm, 0.001);
        double cc = clab / dm1;
        double rr = lim(((cc - low) / (high - low)), 0, 1);

        if (swap) {
            rr = 1.0 - rr;
        }
        return rr;
    }

    public void loadAllWeatherDataFromMongo(List<String> mongoData) {
        List<List<Object>> weatherData = new ArrayList<>();
        resetDoyStaticFields();

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        for (String line : mongoData) {
            // Expected layout: [time, time, rad, temp, rain, hum, wind]
            String[] parts = line.split(",");
            if (parts.length < 7) continue;

            List<Object> rowData = new ArrayList<>();
            try {
                String timeStr = parts[0];
                Date date = dateFormat.parse(timeStr);
                double doy = getDoy(date);

                rowData.add(timeStr);   // 0: time string
                rowData.add(doy);       // 1: DOY
                rowData.add(parts[2]);  // 2: radiation
                rowData.add(parts[3]);  // 3: temperature
                rowData.add(parts[4]);  // 4: rain
                rowData.add(parts[5]);  // 5: relative humidity
                rowData.add(parts[6]);  // 6: wind
                rowData.add("0.0");     // 7: placeholder
                rowData.add("0.0");     // 8: irrigation

                weatherData.add(rowData);
            } catch (ParseException e) {
                // Skip rows whose timestamp cannot be parsed.
            }
        }

        _weatherData = weatherData;
    }

    public void runModel() throws IOException {
        if (_weatherData == null || _weatherData.isEmpty()) {
            return;
        }
        simulate();
    }

    public void ode2InitModel(Double startTime, Double endTime) {
    }

    public List<Double> ode2initValuesTime0() {
        List<Double> yi = new ArrayList<>();
        for (int index = 0; index < 9 + _nsl * 5; ++index) {
            yi.add(0.0);
        }

        List<Double> iTheta = new ArrayList<>();
        for (int index = 0; index < _nsl; ++index) {
            iTheta.add(_iTheta + index * _thg);
        }

        List<Double> iNcont = new ArrayList<>();
        iNcont.add(39.830);
        iNcont.add(10.105);
        iNcont.add(16.050);
        iNcont.add(8.0);
        iNcont.add(8.0);
        for (int index = 5; index < 15; ++index) {
            iNcont.add(0.0);
        }

        double iNRT = 6.0;
        yi.set(1, _cuttingDryMass);
        yi.set(6, _cuttingDryMass);

        yi.set(9 + _nsl, iNRT);

        for (int i = 0; i < _nsl; ++i) {
            yi.set(9 + 2 * _nsl + i, iTheta.get(i));
            yi.set(9 + 3 * _nsl + i, iNcont.get(i) * this.fertilizationLevel / 100);
            yi.set(9 + 4 * _nsl + i, _cuttingDryMass * 30.0 / _nsl);
        }

        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);

        return yi;
    }

    int _iwdRowNum = 1;

    // Find the weather-data row whose DOY is closest to t and return derived weather inputs.
    public List<Double> getWeatherData(double t) {

        final int iDOY = _iDOY;
        final int iRadiation = 2;
        final int iTemp = 3;
        final int iRain = 4;
        final int iRH = 5;
        final int iWind = 6;

        if (_iwdRowNum < 0) _iwdRowNum = 0;
        if (_iwdRowNum >= _weatherData.size()) _iwdRowNum = _weatherData.size() - 1;

        double doy = Double.parseDouble(_weatherData.get(_iwdRowNum).get(iDOY).toString());
        double nextDoy = (_iwdRowNum + 1 < _weatherData.size())
                ? Double.parseDouble(_weatherData.get(_iwdRowNum + 1).get(iDOY).toString())
                : doy + 1.0;

        while (t >= nextDoy && _iwdRowNum + 1 < _weatherData.size()) {
            ++_iwdRowNum;
            doy = Double.parseDouble(_weatherData.get(_iwdRowNum).get(iDOY).toString());
            nextDoy = (_iwdRowNum + 1 < _weatherData.size())
                    ? Double.parseDouble(_weatherData.get(_iwdRowNum + 1).get(iDOY).toString())
                    : doy + 1.0;
        }

        while (t < doy && _iwdRowNum > 0) {
            --_iwdRowNum;
            doy = Double.parseDouble(_weatherData.get(_iwdRowNum).get(iDOY).toString());
            nextDoy = (_iwdRowNum + 1 < _weatherData.size())
                    ? Double.parseDouble(_weatherData.get(_iwdRowNum + 1).get(iDOY).toString())
                    : doy + 1.0;
        }

        final int n = _iwdRowNum;

        double dt = Math.max(1e-6, nextDoy - doy);

        double rain = Double.parseDouble(_weatherData.get(n).get(iRain).toString());
        double temp = Double.parseDouble(_weatherData.get(n).get(iTemp).toString());
        double radiation = Double.parseDouble(_weatherData.get(n).get(iRadiation).toString());
        double relativeHumidity = Double.parseDouble(_weatherData.get(n).get(iRH).toString());
        double wind = Double.parseDouble(_weatherData.get(n).get(iWind).toString());

        // radiation đầu vào ở MJ/m²/h (sensor "rad"); PPFD (μmol/m²/s) = (W/m²) × 2.15;
        // 1 MJ/m²/h ≈ 277.78 W/m² ⇒ hệ số 597.22.
        double ppfd = radiation * 597.22;
        double et0 = 24 * hourlyET(temp, radiation, relativeHumidity, wind, doy, 21.0075, 105.5416, 16, 105.5416, 2.5);

        List<Double> YR = new ArrayList<>();
        YR.add(rain);       // wd[0]
        YR.add(temp);       // wd[1]
        YR.add(ppfd);       // wd[2]
        YR.add(et0);        // wd[3]
        YR.add(0.0);        // wd[4] irrigation
        YR.add(dt);         // wd[5] dt
        YR.add((double) n); // wd[6] row index

        return YR;
    }

    int pdt = 1;
    int _printSize = 366;

    public void simulate() {
        _iStart = Double.parseDouble(_weatherData.get(1).get(_iDOY).toString());
        _iend = Double.parseDouble(_weatherData.get(_weatherData.size() - 2).get(_iDOY).toString());
        _autoIrrigateTime = -1;
        caculateFcthresholdAndIrrigationRate();
        double t = _iStart;
        List<Double> w = ode2initValuesTime0();
        double dt = (double) 60 / (60 * 24); // 1 hour step
        int ps = min(_printSize, (int) ceil((_iend - _iStart) / pdt));
        List<Double> ptime = new ArrayList<>();
        for (int index = 0; index < ps; ++index) {
            ptime.add(_iStart + (double) index * pdt);
        }
        for (int i = 0; i <= 8; i++) {
            List<Double> innerList = new ArrayList<>(Collections.nCopies(ptime.size(), 0.0));
            _results.add(innerList);
        }
        t = _iStart;
        for (int i = 0; i < ptime.size(); ++i) {
            List<Double> wd = getWeatherData(t);
            double tw = t + wd.get(5);
            while (t < ptime.get(i) - 0.5 * dt) {
                double wddt = max(1e-10, min(min(dt, wd.get(5)), ptime.get(i) - t));

                // Auto-irrigation controller: morning (06:00-07:00) or afternoon (16:00-17:00),
                // soil moisture below threshold, and no irrigation already done this session.
                double currentTh = calculateCurrentThEquiv(w);
                double currentHour = (t % 1.0) * 24.0;
                boolean isMorning = (currentHour >= 6.0 && currentHour < 7.0);
                boolean isAfternoon = (currentHour >= 16.0 && currentHour < 17.0);
                boolean hasIrrigatedSession = (t - _autoIrrigateTime) < 0.5;

                if (!hasIrrigatedSession && (isMorning || isAfternoon) && currentTh < _fcthresold) {
                    _autoIrrigateTime = t;

                    int doyInt = (int) Math.floor(t);
                    int year = 2024 + (doyInt - 1) / 365;
                    int dayOfYear = (doyInt - 1) % 365 + 1;
                    LocalDate date = LocalDate.ofYearDay(year, dayOfYear);
                    double fractionDay = t - Math.floor(t);
                    int hours = (int) (fractionDay * 24);
                    int minutes = (int) ((fractionDay * 24 - hours) * 60);
                    String timeStr = String.format("%s %02d:%02d:00", date.toString(), hours, minutes);

                    HistoryIrrigation history = new HistoryIrrigation();
                    history.setTime(timeStr);
                    history.setSimulationT(t);
                    history.setUserName("Hệ thống tự động");
                    double amountVal = _IrrigationRate * (this.irrigationDuration / 24.0);
                    history.setAmount(amountVal);
                    history.setDuration(this.irrigationDuration * 60); // minutes
                    this.listHistory.add(history);
                }

                double irrigationFlux = 0.0;
                double irrigationDurationDays = _autoIrrigationDuration;
                if (t < _autoIrrigateTime + irrigationDurationDays) {
                    irrigationFlux = _IrrigationRate;
                }

                double rainFromFile = 0.0;
                wd.set(4, rainFromFile + irrigationFlux);

                rk4Step(t - _iStart, w, wddt, wd);
                t += wddt;

                if (t > tw) {
                    wd = getWeatherData(t);
                    tw = t + wd.get(5);
                }
            }

            _results.get(0).set(i, w.get(3) * 10 / _APPi);
            _results.get(1).set(i, w.get(9 + 2 * _nsl));
            _results.get(2).set(i, w.get(9 + 5 * _nsl)); // irrigation
            _results.get(3).set(i, w.get(4) / _APPi);
            _results.get(4).set(i, 100.0 + 100.0 * w.get(8) / max(1.0, w.get(0) + w.get(1) + w.get(2) + w.get(3)));
            _results.get(5).set(i, w.get(9 + 5 * _nsl + 5));
            _results.get(6).set(i, w.get(9 + 3 * _nsl));
            _results.get(8).set(i, t);

            int ri = 9 + 4 * _nsl;
            final double Nopt = 45 * w.get(0) + 2 * w.get(3) + 20 * w.get(1) + 20 * w.get(2);
            _results.get(7).add((w.subList(ri, ri + _nsl))
                    .stream()
                    .reduce((value, element) -> value + element)
                    .orElse(0.0) / Math.max(1.0, Nopt));
        }
    }

    public void rk4Step(double t, List<Double> y, double dt, List<Double> wd) {
        List<Double> yp = new ArrayList<>(y);

        List<Double> r1 = ode2(t, yp, wd);
        double t1 = t + 0.5 * dt;
        double t2 = t + dt;

        intStep(yp, r1, 0.5 * dt);
        List<Double> r2 = ode2(t1, yp, wd);

        for (int i = 0; i < y.size(); i++) {
            yp.set(i, y.get(i));
        }

        intStep(yp, r2, 0.5 * dt);
        List<Double> r3 = ode2(t1, yp, wd);

        for (int i = 0; i < y.size(); i++) {
            yp.set(i, y.get(i));
        }

        intStep(yp, r3, dt);
        List<Double> r4 = ode2(t2, yp, wd);

        for (int i = 0; i < r4.size(); i++) {
            r4.set(i, (r1.get(i) + 2 * (r2.get(i) + r3.get(i)) + r4.get(i)) / 6); // rk4
        }

        intStep(y, r4, dt);
    }

    public List<Double> ode2(double ct, List<Double> y, List<Double> wd) {

        int cnt = -1;
        double LDM = y.get(++cnt);    // Leaf Dry Mass (g)
        double SDM = y.get(++cnt);    // Stem Dry Mass (g)
        double RDM = y.get(++cnt);    // Root Dry Mass (g)
        double SRDM = y.get(++cnt);   // Storage Root Dry Mass (g)
        double LA = y.get(++cnt);     // Leaf Area (m2)

        double mDMl = y.get(++cnt);
        double mDMs = y.get(++cnt);
        ++cnt; // mDMsr placeholder
        double Clab = y.get(++cnt);   // labile carbon pool
        ++cnt;
        List<Double> rlL = y.subList(cnt, cnt += _nsl);     // root length per layer (m)

        List<Double> nrtL = y.subList(cnt, cnt += _nsl);    // root tips per layer
        double NRT = 0;
        for (double element : nrtL) {
            NRT += element;
        }
        List<Double> thetaL = y.subList(cnt, cnt += _nsl);  // volumetric soil water content per layer

        List<Double> ncontL = y.subList(cnt, cnt += _nsl);
        List<Double> nuptL = y.subList(cnt, cnt += _nsl);
        double Nupt = 0;
        for (double element : nuptL) {
            Nupt += element;
        }

        double TDM = LDM + SDM + RDM + SRDM + Clab;
        double cDm = 0.43;
        double leafTemp = wd.get(1);
        double TSphot = lim((-0.832097717 + 0.124485738 * leafTemp - 0.002114081 * Math.pow(leafTemp, 2)), 0, 1);
        double TSshoot = lim((-1.5 + 0.125 * leafTemp), 0, 1) * lim((7.4 - 0.2 * leafTemp), 0, 1);
        double TSroot = 1.0;

        List<Double> krootL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            krootL.add(fKroot(thetaL.get(i), rlL.get(i)));
        }
        double Kroot = krootL.stream().mapToDouble(Double::doubleValue).sum();
        Kroot = Math.max(1e-8, Kroot);

        double thEquiv;
        if (Kroot > 1e-8) {
            double sumThetaKroot = 0.0;
            for (int i = 0; i < _nsl; ++i) {
                sumThetaKroot += thetaL.get(i) * krootL.get(i);
            }
            thEquiv = sumThetaKroot / Kroot;
        } else {
            thEquiv = thetaL.get(0);
        }

        double WStrans = fWstress(0.05, 0.5, thEquiv);
        double WSphot = fWstress(0.05, 0.3, thEquiv);
        double WSshoot = fWstress(0.2, 0.55, thEquiv);
        double WSroot = 1;
        double WSleafSenescence = 1.0 - fWstress(0.0, 0.2, thEquiv);

        // Water in soil — irrigation comes from auto-controller (when enabled) via wd[4].
        double irrigation = this.autoIrrigation ? wd.get(4) : 0.0;
        double precipitation = this.scaleRain / 100 * wd.get(0) + irrigation;
        double ET0reference = wd.get(3);
        double ETrainFactor = (precipitation > 0) ? 1 : 0;
        double kdf = -0.47;
        double ll = Math.exp(kdf * LA / _APPi);
        double cropFactor = 1 - ll * 0.8;
        double transpiration = cropFactor * ET0reference;
        double swfe = Math.pow(relTheta(thetaL.get(0)), 2.5);
        double actFactor = Math.max(ll * swfe, ETrainFactor);
        double evaporation = actFactor * ET0reference;

        double actualTranspiration = transpiration * WStrans;
        List<Double> wuptrL = multiplyListsWithConstant(krootL, actualTranspiration / Kroot);

        double drain = 0.0;
        List<Double> qFlow = new ArrayList<>(Collections.nCopies(_nsl + 1, 0.0));
        qFlow.set(0, (precipitation - evaporation) / (_lw * 1000.0));

        for (int i = 1; i < qFlow.size(); ++i) {
            double thdown = (i < _nsl)
                    ? thetaL.get(i)
                    : (_zerodrain)
                      ? thetaL.get(i - 1) + _thg
                      : _thm;
            qFlow.set(i, qFlow.get(i) +
                    (thetaL.get(i - 1) + _thg - thdown) * _rateFlow * (thetaL.get(i - 1) / _ths) +
                    4.0 * Math.max(thetaL.get(i - 1) - _ths, 0));
        }

        List<Double> dThetaDt = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            double dTheta = qFlow.get(i) - qFlow.get(i + 1) - wuptrL.get(i) / (_lw * 1000.0);
            dThetaDt.add(dTheta);
        }

        drain = qFlow.get(_nsl) * _lw * 1000;

        double Nopt = 45 * LDM + 7 * SRDM + 20 * SDM + 20 * RDM;
        double NuptLimiter = 1.0 - fNSstress(Nupt, 2.0 * Nopt, 3.0 * Nopt);
        List<Double> nuptrL = new ArrayList<>();
        for (int i = 0; i < _nsl; i++) {
            double nuptr = monod(ncontL.get(i) * _BD / (1000 * thetaL.get(i)),
                    NuptLimiter * rlL.get(i) * 0.8,
                    12.0 * 0.5);
            nuptrL.add(nuptr);
        }

        List<Double> ncontrL = new ArrayList<>(Collections.nCopies(_nsl, 0.0));
        List<Double> _NminR_l = new ArrayList<>();
        for (int d = 0; d < _nsl; d++) {
            double nminR = this.fertilizationLevel / 100.0 *
                    36.0 / (_lvol * _BD) /
                    Math.pow(d + 1, 2);
            _NminR_l.add(nminR);
        }

        for (int i = 0; i < _nsl; i++) {
            ncontrL.set(i, _NminR_l.get(i));
            ncontrL.set(i, ncontrL.get(i) - nuptrL.get(i) / (_BD * _lvol));
            double Nl = ncontL.get(i);
            double Nu = (i > 0) ? ncontL.get(i - 1) : -ncontL.get(i);
            double Nd = (i < (_nsl - 1)) ? ncontL.get(i + 1) : 0.0;
            ncontrL.set(i, ncontrL.get(i) + qFlow.get(i) * (Nu + Nl) / 2.0 - qFlow.get(i + 1) * (Nl + Nd) / 2.0);
        }

        double NSphot = (Nopt > 1e-3) ? fNSstress(Nupt, 0.7 * Nopt, Nopt) : 1.0;
        double NSshoot = (Nopt > 1e-3) ? fNSstress(Nupt, 0.7 * Nopt, 0.9 * Nopt) : 1.0;
        double NSroot = (Nopt > 1e-3) ? fNSstress(Nupt, 0.5 * Nopt, 0.7 * Nopt) : 1.0;
        double NSleafSenescence = (Nopt > 1.0) ? 1.0 - fNSstress(Nupt, 0.8 * Nopt, Nopt) : 0.0;

        // sink strength
        double mGRl = logistic(ct, 0.3, 70, 0, 0.9);
        double mGRld = logistic(ct, 0.0, 70.0 + _leafAge, 0.1, -0.90);
        double mGRs = logistic(ct, 0.2, 95, 0.219, 1.87) +
                logistic(ct, 0.0, 209, 0.219, 1.87 - 0.84);
        double mGRr = 0.02 + (0.2 + Math.exp(-0.8 * ct - 0.2)) * mGRl;
        double mGRsr = Math.min(7.08, Math.pow(Math.max(0.0, (ct - 32.3) * 0.02176), 2));
        double mDMr = 0.02 * ct + 1.25 + 0.25 * ct -
                1.25 * Math.exp(-0.8 * ct) * mGRl +
                (0.25 + Math.exp(-0.8 * ct)) * mDMl;

        double CSphot = getStress(Clab, TDM, 0.05, -9999.9, true);
        double CSshoota = getStress(Clab, TDM, -0.05, -9999.9, false);
        double CSshootl = lim((5 - LA / _APPi), 0, 1);
        double CSshoot = CSshoota * CSshootl;
        double CSroot = getStress(Clab, TDM, -0.03, -9999.9, false);
        double CSsrootl = getStress(Clab, TDM, -0.0, -9999.9, false);
        double CSsrooth = getStress(Clab, TDM, 0.01, 0.20, false);
        double starchRealloc = getStress(Clab, TDM, -0.2, -0.1, true) * -0.05 * SRDM;
        double CSsroot = CSsrootl + 2 * CSsrooth;
        double SFleaf = WSshoot * NSshoot * TSshoot * CSshootl;
        double SFstem = WSshoot * NSshoot * TSshoot * CSshoot;
        double SFroot = WSroot * NSroot * TSroot * CSroot;
        double SFsroot = CSsroot;

        double CsinkL = cDm * mGRl * SFleaf;
        double CsinkS = cDm * mGRs * SFstem;
        double CsinkR = cDm * mGRr * SFroot;
        double CsinkSR = cDm * mGRsr * SFsroot - starchRealloc;
        double Csink = CsinkL + CsinkS + CsinkR + CsinkSR;

        // biomass partitioning
        double a2l = CsinkL / Math.max(1e-10, Csink);
        double a2s = CsinkS / Math.max(1e-10, Csink);
        double a2r = CsinkR / Math.max(1e-10, Csink);
        double a2sr = CsinkSR / Math.max(1e-10, Csink);

        // carbon to growth
        double CFG = Csink;
        double IDM = Csink / cDm;
        double PPFD = wd.get(2);
        double SFphot = Math.min(Math.min(TSphot, WSphot), Math.min(NSphot, CSphot));
        double CFR = photoFixMean(PPFD, LA / _APPi, -0.47, 29.37 * SFphot, 0.05553, 0.90516);

        double SDMR = a2s * IDM;
        double SRDMR = a2sr * IDM;
        double SLA = fSLA(ct);
        double LDRstress = WSleafSenescence * NSleafSenescence * LDM * -1.0;
        double LDRage = mGRld * ((mDMl > 0) ? LDM / mDMl : 1.0);
        if (LDRstress > 1e-10 || LDRage > 1e-10) {
            throw new AssertionError("LDRstress: " + LDRstress + " LDRage: " + LDRage);
        }
        double LDRm = Math.max(-LDM, LDRstress + LDRage);
        double LDRa = Math.max(-LA, fSLA(Math.max(0.0, ct - _leafAge)) * LDRm);
        double LAeR = SLA * a2l * IDM + LDRa;
        double LDMR = a2l * IDM + LDRm;

        double RDMR = a2r * IDM;
        double RLR = _SRL * RDMR;
        List<Double> rlrL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            double ln1 = RLR * nrtL.get(i) / NRT;
            rlrL.add(ln1);
        }
        double ln0 = 0.0;
        List<Double> nrtrL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            double ln1 = rlrL.get(i);
            nrtrL.add(ln1 * 60.0 + Math.max(0, (ln0 - ln1 - 6.0 * _lw) * 10.0 / _lw));
            ln0 = ln1;
        }

        double mRR = 0.003 * RDM + 0.0002 * SRDM + 0.003 * LDM + 0.0002 * SDM;
        double gRR = 1.8 * RDMR + 0.2 * SRDMR + 1.8 * (LDMR - LDRm) + 0.4 * SDMR;
        double RR = mRR + gRR;

        double ClabR = (CFR - CFG - RR) / cDm;
        cnt = -1;
        List<Double> YR = new ArrayList<>();
        YR.add(++cnt, LDMR);
        YR.add(++cnt, SDMR);
        YR.add(++cnt, RDMR);
        YR.add(++cnt, SRDMR);
        YR.add(++cnt, LAeR);
        YR.add(++cnt, mGRl);
        YR.add(++cnt, mGRs);
        YR.add(++cnt, (double) mGRsr);
        YR.add(++cnt, ClabR);
        YR.addAll(rlrL);
        YR.addAll(nrtrL);
        YR.addAll(dThetaDt);
        YR.addAll(ncontrL);
        YR.addAll(nuptrL);

        YR.add((double) irrigation);
        YR.add(wd.get(0)); // rain
        YR.add((double) actualTranspiration);
        YR.add(evaporation);
        YR.add(drain);
        YR.add(CFR);
        YR.add(PPFD);

        return YR;
    }

    public double fWstress(double minv, double maxv, double the) {
        double s = 1 / (maxv - minv);
        double i = -1 * minv * s;
        return lim((i + s * relTheta(the)), 0, 1);
    }

    public double fNSstress(double upt, double low, double high) {
        double rr = (upt - low) / (high - low);
        return lim(rr, 0, 1);
    }

    public double hourlyET(
            final double tempC,
            final double radiation,
            final double relativeHumidity,
            final double wind,
            final double doy,
            final double latitude,
            final double longitude,
            final double elevation,
            final double longZ,
            final double height) {

        final double pi = Math.PI;
        final double hours = (doy % 1) * 24;
        final double tempK = tempC + 273.16;

        // radiation đã ở đơn vị MJ/m²/h (sensor "rad"), Rs trong công thức ET cũng là MJ/m²/h.
        final double Rs = radiation;
        final double P = 101.3 *
                Math.pow((293 - 0.0065 * elevation) / 293, 5.256);
        final double psi = 0.000665 * P;

        final double Delta = 2503 *
                Math.exp((17.27 * tempC) / (tempC + 237.3)) /
                Math.pow(tempC + 237.3, 2);
        final double eaSat = 0.61078 *
                Math.exp((17.269 * tempC) / (tempC + 237.3));
        final double ea = (relativeHumidity / 100) * eaSat;

        final double DPV = eaSat - ea;
        final double dr = 1 + 0.033 * Math.cos(2 * pi * doy / 365.0);
        final double delta = 0.409 *
                Math.sin(2 * pi * doy / 365.0 - 1.39);
        final double phi = latitude * (pi / 180);
        final double b = 2.0 * pi * (doy - 81.0) / 364.0;

        final double Sc = 0.1645 * Math.sin(2 * b) - 0.1255 * Math.cos(b) - 0.025 * Math.sin(b);
        final double hourAngle = (pi / 12) *
                ((hours + 0.06667 * (longitude * pi / 180.0 - longZ * pi / 180.0) + Sc) - 12.0);
        final double w1 = hourAngle - ((pi) / 24);
        final double w2 = hourAngle + ((pi) / 24);
        final double hourAngleS = Math.acos(-Math.tan(phi) * Math.tan(delta));
        final double w1c = (w1 < -hourAngleS) ? -hourAngleS : (w1 > hourAngleS) ? hourAngleS : (w1 > w2) ? w2 : w1;
        final double w2c = (w2 < -hourAngleS) ? -hourAngleS : (w2 > hourAngleS) ? hourAngleS : w2;

        final double Beta = Math.asin((Math.sin(phi) * Math.sin(delta) + Math.cos(phi) * Math.cos(delta) * Math.cos(hourAngle)));

        final double Ra = (Beta <= 0) ? 1e-45 : ((12 / pi) * 4.92 * dr) *
                                                (((w2c - w1c) * Math.sin(phi) * Math.sin(delta)) +
                                                 (Math.cos(phi) * Math.cos(delta) * (Math.sin(w2) - Math.sin(w1))));

        final double Rso = (0.75 + 2e-05 * elevation) * Ra;

        final double RsRso = (Rs / Rso <= 0.3) ? 0.0 : (Rs / Rso >= 1) ? 1.0 : Rs / Rso;
        final double fcd = (1.35 * RsRso - 0.35 <= 0.05) ? 0.05 : (1.35 * RsRso - 0.35 < 1) ? 1.35 * RsRso - 0.35 : 1;

        final double Rna = ((1 - 0.23) * Rs) -
                (2.042e-10 * fcd * (0.34 - 0.14 * Math.sqrt(ea)) * Math.pow(tempK, 4));

        final double Ghr = (Rna > 0) ? 0.04 : 0.2;
        final double Gday = Rna * Ghr;
        final double wind2 = wind * (4.87 / (Math.log(67.8 * height - 5.42)));
        final double windf = (radiation > 1e-6) ? 0.25 : 1.7;

        final double EThourly = ((0.408 * Delta * (Rna - Gday)) +
                (psi * (66 / tempK) * wind2 * (DPV))) /
                (Delta + (psi * (1 + (windf * wind2))));

        return EThourly;
    }

    void intStep(final List<Double> y, final List<Double> r, final double dt) {
        assert (y.size() == r.size());
        for (int i = 0; i < y.size(); ++i) {
            y.set(i, y.get(i) + dt * r.get(i));
        }
    }

    // Recompute thEquiv (root-weighted soil moisture) from current state vector w —
    // mirrors the calculation inside ode2 so the auto-irrigation controller can poll it.
    public double calculateCurrentThEquiv(List<Double> w) {
        int rlL_startIndex = 9;
        int thetaL_startIndex = 9 + 2 * _nsl;

        List<Double> rlL = w.subList(rlL_startIndex, rlL_startIndex + _nsl);
        List<Double> thetaL = w.subList(thetaL_startIndex, thetaL_startIndex + _nsl);

        List<Double> krootL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            krootL.add(fKroot(thetaL.get(i), rlL.get(i)));
        }

        double Kroot = krootL.stream().mapToDouble(Double::doubleValue).sum();
        Kroot = Math.max(1e-8, Kroot);

        if (Kroot > 1e-8) {
            double sumThetaKroot = 0.0;
            for (int i = 0; i < _nsl; ++i) {
                sumThetaKroot += thetaL.get(i) * krootL.get(i);
            }
            return sumThetaKroot / Kroot;
        }
        return thetaL.get(0);
    }

    public void caculateFcthresholdAndIrrigationRate() {
        _fcthresold = this.fieldCapacity;
        _fcthresold *= (_ths - _thr) / 100;
        _fcthresold += _thr;

        _autoIrrigationDuration = this.irrigationDuration / 24; // hours → days
        double dhr = this.dripRate;             // l/hour
        double dhd = this.distanceBetweenHole;  // cm
        double dld = this.distanceBetweenRow;   // cm
        _IrrigationRate = dhr * 24.0 / (dhd * dld / 10000.0); // mm/day
    }

    public static void resetDoyStaticFields() {
        previousDoy = -1;
        doyOffset = 0;
    }
}
