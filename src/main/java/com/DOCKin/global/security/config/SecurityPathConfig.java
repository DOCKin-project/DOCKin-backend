package com.DOCKin.global.security.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "security")
@Getter
@Setter
public class SecurityPathConfig {
    private List<String> whitelist;

    public String[] getWhiteListArray(){
        return whitelist.toArray(new String[0]);
    }
}