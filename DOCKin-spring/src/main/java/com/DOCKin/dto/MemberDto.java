package com.DOCKin.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder

//멤버조회 Dto
public class MemberDto {
    private String userId;
    private String name;
    private String password;
}
