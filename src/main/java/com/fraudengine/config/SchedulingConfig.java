package com.fraudengine.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the one scheduled task in the service: the rule snapshot's backstop refresh.
 *
 * <p>Its own class rather than an annotation on an unrelated one, so what scheduling exists for
 * here is answerable by finding this file.
 */
@Configuration
@EnableScheduling
class SchedulingConfig {}
