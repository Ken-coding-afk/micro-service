package com.example.sample.model;

/** 明确允许输出的业务投影，不包含持久化中的租户和所有者字段。 */
public record Item(
    @com.fasterxml.jackson.annotation.JsonProperty(required = true)
        @com.fasterxml.jackson.annotation.JsonSetter(
            nulls = com.fasterxml.jackson.annotation.Nulls.FAIL)
        String id,
    @com.fasterxml.jackson.annotation.JsonProperty(required = true)
        @com.fasterxml.jackson.annotation.JsonSetter(
            nulls = com.fasterxml.jackson.annotation.Nulls.FAIL)
        String name) {}
