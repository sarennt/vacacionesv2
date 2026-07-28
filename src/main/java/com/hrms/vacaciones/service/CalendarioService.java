package com.hrms.vacaciones.service;

import com.hrms.vacaciones.dto.RangoSemanaVip;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;

@Service
public class CalendarioService {

    /**
     * Calcula la ventana inamovible de apertura de 5 días.
     * Busca el último viernes del mes y retrocede al lunes de esa misma semana.
     */
    public RangoSemanaVip calcularSemanaPerfecta(int anio, int mes) {

        // 1. Obtenemos el mes y año en cuestión
        YearMonth mesEnCurso = YearMonth.of(anio, mes);

        // 2. Nos posicionamos en el último día del mes
        LocalDate ultimoDiaDelMes = mesEnCurso.atEndOfMonth();

        // 3. El motor busca el último viernes del mes en curso
        LocalDate ultimoViernes = ultimoDiaDelMes.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));

        // 4. Retrocedemos al lunes de esa misma semana (viernes menos 4 días)
        LocalDate lunesDeEsaSemana = ultimoViernes.minusDays(4);

        // 5. Retornamos el bloque exacto de 5 días
        return new RangoSemanaVip(lunesDeEsaSemana, ultimoViernes);
    }

    /**
     * Método auxiliar para saber si el día de hoy cae dentro de la Semana Perfecta
     */
    public boolean esSemanaAperturaVip(LocalDate fechaActual) {
        RangoSemanaVip rango = calcularSemanaPerfecta(fechaActual.getYear(), fechaActual.getMonthValue());

        // Validamos que la fecha actual esté entre el lunes y el viernes calculados
        return !fechaActual.isBefore(rango.getFechaInicio()) && !fechaActual.isAfter(rango.getFechaFin());
    }
}