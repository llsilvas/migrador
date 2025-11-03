/*
 *  Copyright (c) 2020 Prodesp Tecnologia da Informação
 *
 */
package prodesp.bio.migrador.infra.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Wellerson Lopes <welsilva@sp.gov.br>
 */
public class Util {

    private static final Logger LOG = LoggerFactory.getLogger(Util.class);

    private static final ObjectMapper mapper;

    static {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setTimeZone(TimeZone.getDefault());
    }

    public static void sleep(long t) {
        try {
            Thread.sleep(t);
        } catch (InterruptedException ex) {
        }
    }


    private static Pattern rgSpPattern = Pattern.compile("^([0-9]{3,})\\-([0-9xX])([2-9]?)$");

    public static String[] splitRg(String rgSp) {
        String[] split = null;
        if (rgSp != null) {
            Matcher matcher = rgSpPattern.matcher(rgSp);
            if (matcher.find()) {
                split = new String[]{matcher.group(1), matcher.group(2), matcher.group(3)};
                split[1] = split[1].toUpperCase();
                if (split[2] != null && split[2].isEmpty()) {
                    split[2] = null;
                }
            }
        }
        return split;
    }

    public static ObjectMapper getObjectMapper() {
        return mapper;
    }

    public static String getStackTrace(Exception ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        return sw.toString();
    }

    public static LocalDateTime convertToLocalDateTime(Date dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    public static LocalDate convertToLocalDate(Date dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    public static Date convertToDate(LocalDate localDate) {
        if (localDate == null) {
            return null;
        }
        return Date.from(localDate.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant());
    }

    public static Date convertToDate(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }

    public static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setTimeZone(TimeZone.getDefault());
        return mapper;
    }

    public static String formatarTamanho(String texto, int tamanho) {

        if (texto == null) {
            return padText("", tamanho);
        }

        if (texto.length() <= tamanho) {
            return padText(texto, tamanho);
        }

        LOG.warn("A mensagem {} foi truncada em {} caracteres", texto, tamanho);
        return truncateText(texto, tamanho);
    }

    public static String truncateText(String text, int size) {
        return text.substring(0, size);
    }

    public static String padText(String text, int size) {
        StringBuilder paddedText = new StringBuilder(text);
        while (paddedText.length() < size) {
            paddedText.append(" ");
        }
        return paddedText.toString();
    }

    public static boolean isInvalidDate(Date inputDate, Long milliseconds) {
        long now = System.currentTimeMillis();
        long diffMillis = inputDate.getTime() - now;

        if (diffMillis > milliseconds) {
            return true;
        }

        return false;
    }

}
