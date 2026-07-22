package com.example.demo.data;

import lombok.Data;
@Data

public class ResponseDTO {

    private boolean success;
    private String message;
    private Object data;

    public ResponseDTO() {
    }

    public ResponseDTO(boolean success, String message, Object data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static ResponseDTO ok(Object data) {
        return new ResponseDTO(true, "OK", data);
    }
}
