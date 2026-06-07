package com.duoc.cloudnative.model;

import lombok.Data;
import java.time.LocalDate;

@Data
public class GuiaDespacho {
    private String id;
    private String numeroGuia;
    private String transportista;
    private LocalDate fecha;
    private String rutaS3;
}