package org.os.gitbase.git.dto;

/** A repository label. {@code color} is a 6-digit hex value without the leading '#'. */
public record LabelDto(String name, String color, String description) {}
