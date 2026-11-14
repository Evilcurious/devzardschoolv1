package com.erpschool.saas.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin SQL helper for the SaaS layer. Rows come back as ordered maps keyed by
 * the column alias, with JSON columns parsed and timestamps as {@code Instant}.
 * Every statement uses named bind parameters; nothing is concatenated from input.
 */
@Component
public class Db {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Db(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Parameter map that, unlike {@code Map.of}, accepts null values. */
    public static Map<String, Object> p(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    public List<Map<String, Object>> list(String sql, Map<String, Object> params) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(convert(row));
        }
        return out;
    }

    public Map<String, Object> one(String sql, Map<String, Object> params) {
        List<Map<String, Object>> rows = list(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(String sql, Map<String, Object> params) {
        return jdbc.update(sql, params);
    }

    public long count(String sql, Map<String, Object> params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    public <T> T scalar(String sql, Map<String, Object> params, Class<T> type) {
        List<T> rows = jdbc.queryForList(sql, params, type);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value cannot be stored as JSON", e);
        }
    }

    private Map<String, Object> convert(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(e.getKey(), convert(e.getValue()));
        }
        return out;
    }

    private Object convert(Object value) {
        // The PostgreSQL driver is a runtime-only dependency, so its JSON wrapper is matched by name.
        if (value != null && "org.postgresql.util.PGobject".equals(value.getClass().getName())) {
            String text = value.toString();
            if (text == null) {
                return null;
            }
            try {
                return mapper.readValue(text, Object.class);
            } catch (JsonProcessingException e) {
                return text;
            }
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return value;
    }
}
