package com.hengsheng.fund;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status,String code,String message) { super(message);this.status=status;this.code=code; }
    public static ApiException bad(String message) { return new ApiException(400,"INVALID_REQUEST",message); }
    public static ApiException conflict(String message) { return new ApiException(409,"BUSINESS_CONFLICT",message); }
    public static ApiException missing() { return new ApiException(404,"NOT_FOUND","记录不存在"); }
}
