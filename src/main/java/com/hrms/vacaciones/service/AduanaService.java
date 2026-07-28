package com.hrms.vacaciones.service;

import org.springframework.stereotype.Service;
import java.time.DayOfWeek;
import java.time.LocalDate;

@Service
public class AduanaService {

    /**
     * Evalúa si un operador tiene acceso a la pantalla de solicitud según su saldo y la etapa del mes.
     */
    public boolean puedeSolicitarVacaciones(double saldoOperador, LocalDate fechaActual, DayOfWeek diaAsignadoQuintil, boolean esSemanaAperturaVip) {

        // 1. REGLA: Saldo Negativo -> Bloqueado Totalmente siempre en cualquier escenario.
        if (saldoOperador < 0) {
            return false;
        }

        DayOfWeek diaHoy = fechaActual.getDayOfWeek();

        // 2. REGLA: Sábado (Repechaje) o Resto del Mes -> Acceso libre para Positivos y Ceros.
        if (!esSemanaAperturaVip || diaHoy == DayOfWeek.SATURDAY) {
            return saldoOperador >= 0;
        }

        // --- SI LLEGAMOS AQUÍ, ESTAMOS EN LA SEMANA VIP DE LUNES A VIERNES ---

        // 3. REGLA: Saldo Cero (Con proporcional) -> Bloqueado de L-V durante la semana VIP.
        if (saldoOperador == 0) {
            return false;
        }

        // 4. REGLA: Saldo Positivo (Quintil Asignado) -> Acceso única y exclusivamente en su día asignado.
        return diaHoy == diaAsignadoQuintil;
    }

    /**
     * Metodito auxiliar para traducir el enum DayOfWeek a texto en español
     * y mostrarlo directo en los mensajes de error del front.
     */
    public String obtenerNombreDiaEspanol(DayOfWeek dia) {
        return switch (dia) {
            case MONDAY -> "LUNES";
            case TUESDAY -> "MARTES";
            case WEDNESDAY -> "MIÉRCOLES";
            case THURSDAY -> "JUEVES";
            case FRIDAY -> "VIERNES";
            case SATURDAY -> "SÁBADO";
            case SUNDAY -> "DOMINGO";
        };
    }
}