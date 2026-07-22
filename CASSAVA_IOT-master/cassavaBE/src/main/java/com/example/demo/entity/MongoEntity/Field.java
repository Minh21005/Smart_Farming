package com.example.demo.entity.MongoEntity;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Date;
@Data
@Getter
@Setter
@Document(collection = "field")
public class Field {

   /** @Id
    private String id;

    private String idUser = "Admin";

    private double acreage = 1000;

    private boolean autoIrrigation = true;

    private double fieldCapacity = 0.8;

    private int irrigationDuration = 30;

    private double distanceBetweenRow = 1.2;

    private double distanceBetweenHole = 0.3;

    private double dripRate = 2;

    private double scaleRain = 0.7;

    private int numberOfHoles = 100;

    private double fertilizationLevel = 1;

    private int dAP = 10;


    // 🔥 luôn set khi tạo object
    private Date startTime = new Date();

    private boolean isIrrigating = false;
*/
    @Id
    private String id;
    private String name;
    private String groupId;
    private double acreage;
    private double fieldCapacity;
    private double distanceBetweenRow;
    private double distanceBetweenHole;
    private double dripRate;
    private boolean autoIrrigation;
    private int numberOfHoles;
    private double fertilizationLevel;
    private Integer valveId;

    // SIMULATION = ruộng mô phỏng (không gửi lệnh tưới ra edge)
    // OPERATION  = ruộng thực thi (sẽ publish MQTT khi bridge sẵn sàng)
    private String mode = "SIMULATION";

    // Các biến bạn muốn để mặc định (không truyền từ JSON)
    private String idUser = "69ccc364a1e7905cc9356ce3"; // Default ID
    private int irrigationDuration = 2;
    private double scaleRain = 100;
    private Date startTime = new Date();
    private Date endTime; // null = vụ đang chạy → mô phỏng tới now()
    private int dAP = 1;// DEFAULT START = 1
    private boolean isIrrigating = false;
    /**
     * No-arg constructor for MongoDB/Jackson deserialization
     */
    public Field() {}

    /**
     * Constructor with default field values.
     * Usage: Field fieldTest = new Field("fieldTest");
     */
    public Field(String name) {
        this.name = name;
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
        this.startTime = new Date();
        this.dAP = 1;
        this.isIrrigating = false;
    }

    /**
     * Full constructor
     */
    public Field(String name, double acreage, double fieldCapacity,
                 double distanceBetweenRow, double distanceBetweenHole, double dripRate,
                 boolean autoIrrigation, int numberOfHoles, double fertilizationLevel) {
        this.name = name;
        this.acreage = acreage;
        this.fieldCapacity = fieldCapacity;
        this.autoIrrigation = autoIrrigation;
        this.distanceBetweenHole = distanceBetweenHole;
        this.distanceBetweenRow = distanceBetweenRow;
        this.dripRate = dripRate;
        this.fertilizationLevel = fertilizationLevel;
        this.numberOfHoles = numberOfHoles;

    }
    // ===== GETTERS / SETTERS =====

    public String getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getIdUser() { return idUser; }
    public void setIdUser(String idUser) { this.idUser = idUser; }

    public double getAcreage() { return acreage; }
    public void setAcreage(double acreage) { this.acreage = acreage; }

    public boolean isAutoIrrigation() { return autoIrrigation; }
    public void setAutoIrrigation(boolean autoIrrigation) { this.autoIrrigation = autoIrrigation; }

    public double getFieldCapacity() { return fieldCapacity; }
    public void setFieldCapacity(double fieldCapacity) { this.fieldCapacity = fieldCapacity; }

    public int getIrrigationDuration() { return irrigationDuration; }
    public void setIrrigationDuration(int irrigationDuration) { this.irrigationDuration = irrigationDuration; }

    public double getDistanceBetweenRow() { return distanceBetweenRow; }
    public void setDistanceBetweenRow(double distanceBetweenRow) { this.distanceBetweenRow = distanceBetweenRow; }

    public double getDistanceBetweenHole() { return distanceBetweenHole; }
    public void setDistanceBetweenHole(double distanceBetweenHole) { this.distanceBetweenHole = distanceBetweenHole; }

    public double getDripRate() { return dripRate; }
    public void setDripRate(double dripRate) { this.dripRate = dripRate; }

    public double getScaleRain() { return scaleRain; }
    public void setScaleRain(double scaleRain) { this.scaleRain = scaleRain; }

    public int getNumberOfHoles() { return numberOfHoles; }
    public void setNumberOfHoles(int numberOfHoles) { this.numberOfHoles = numberOfHoles; }

    public double getFertilizationLevel() { return fertilizationLevel; }
    public void setFertilizationLevel(double fertilizationLevel) { this.fertilizationLevel = fertilizationLevel; }

    public int getDAP() { return dAP; }
    public void setDAP(int dAP) { this.dAP = dAP; }

    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }

    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }

    public boolean isIrrigating() { return isIrrigating; }
    public void setIrrigating(boolean irrigating) { isIrrigating = irrigating; }

    public Integer getValveId() { return valveId; }
    public void setValveId(Integer valveId) { this.valveId = valveId; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}