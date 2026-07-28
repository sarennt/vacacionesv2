package com.hrms.vacaciones.dto;

public record RespuestaJefeRequest(
        Integer idSolicitud,
        boolean aprobado,
        String comentario
) {}
