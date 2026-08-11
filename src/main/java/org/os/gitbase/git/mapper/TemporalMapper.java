package org.os.gitbase.git.mapper;

import org.mapstruct.Mapper;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Shared {@code LocalDateTime → epoch millis} conversion.
 *
 * <p>Every DTO exposes timestamps as epoch millis so the frontend never has to parse a date
 * format. Declared as a mapper rather than a static helper so other mappers can pull it in via
 * {@code uses = TemporalMapper.class} and MapStruct wires the conversion automatically.
 */
@Mapper(componentModel = "spring")
public interface TemporalMapper {

    default Long toEpochMillis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
