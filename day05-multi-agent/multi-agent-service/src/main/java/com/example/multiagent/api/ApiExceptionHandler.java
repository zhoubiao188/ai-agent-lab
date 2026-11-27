package com.example.multiagent.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 参数错就 400 并说清哪个参数,不要把它们变成 500 —— 500 会把"用户传错"伪装成"服务坏了"。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("请求参数不合法");
        detail.setDetail(String.valueOf(e.getMessage()));
        return detail;
    }
}
