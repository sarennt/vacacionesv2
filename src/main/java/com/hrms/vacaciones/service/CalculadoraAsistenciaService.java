package com.hrms.vacaciones.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;

@Slf4j
@Service
public class CalculadoraAsistenciaService {

    // ✨ 1. CÁLCULO DE TIEMPO EFECTIVO REAL (Para Descuentos y Aduana IMSS)
    public double calcularHorasEfectivas(LocalTime entradaCruda, LocalTime salidaCruda) {
        // Se usa la hora exacta del reloj checador para no perder evidencia de retardos
        long minutosTrabajados = Duration.between(entradaCruda, salidaCruda).toMinutes();

        // Soporte para turnos nocturnos (cruzan la medianoche)
        if (minutosTrabajados < 0) {
            minutosTrabajados += 24 * 60;
        }

        double horasEfectivas = minutosTrabajados / 60.0;

        // La regla de hierro: Si es menor a 1.5 hrs, se anula el día
        if (horasEfectivas < 1.5) {
            return 0.0; // El servicio deberá marcar esto como FALTA o PSG
        }

        return horasEfectivas;
    }

    // ✨ 2. FILTRO DE TIEMPO EXTRA (Redondeo Extremista)
    public double calcularTiempoExtra(LocalTime entradaCruda, LocalTime salidaCruda, double horasJornadaOficial) {
        LocalTime entradaRedondeada = redondearParaTiempoExtra(entradaCruda, true);
        LocalTime salidaRedondeada = redondearParaTiempoExtra(salidaCruda, false);

        long minutosRedondeados = Duration.between(entradaRedondeada, salidaRedondeada).toMinutes();
        if (minutosRedondeados < 0) {
            minutosRedondeados += 24 * 60;
        }

        double horasParaExtra = minutosRedondeados / 60.0;
        double diferencia = horasParaExtra - horasJornadaOficial;

        // Solo se paga si acumula al menos 1 hora completa extra sobre su jornada
        if (diferencia >= 1.0) {
            return diferencia;
        }
        return 0.0;
    }

    // ✨ 3. EL MOTOR EXTREMISTA (Castigos y Minuto de Gracia Exclusivo de Salida)
    private LocalTime redondearParaTiempoExtra(LocalTime hora, boolean esEntrada) {
        int min = hora.getMinute();

        if (esEntrada) {
            // Cero tolerancia en entradas. Se castiga directo al siguiente bloque de 30 mins.
            if (min > 0 && min <= 30) return hora.withMinute(30);
            if (min > 30) return hora.plusHours(1).withMinute(0);
        } else {
            // Regalo de 1 minuto EXCLUSIVO en salidas (18:29 -> 18:30 | 17:59 -> 18:00)
            if (min == 29) return hora.withMinute(30);
            if (min == 59) return hora.plusHours(1).withMinute(0);

            // Castigo al bloque anterior si sale antes (Si sale 18:28, el tiempo extra se corta a las 18:00)
            if (min >= 0 && min < 30) return hora.withMinute(0);
            if (min >= 30) return hora.withMinute(30);
        }
        return hora;
    }

    // ✨ 4. EL JUEZ DE LA SEMANA (Motor con Reglas 1 a 8)
    public String determinarTurnoDominante(List<String> turnosCronologicos) {
        if (turnosCronologicos.isEmpty()) return "FALTA";

        Map<String, Integer> conteo = new HashMap<>();
        for (String t : turnosCronologicos) {
            conteo.put(t, conteo.getOrDefault(t, 0) + 1);
        }

        int count12HRS = conteo.getOrDefault("12HRS", 0);
        int count3RO = conteo.getOrDefault("3RO", 0);
        int count2DO = conteo.getOrDefault("2DO", 0);

        // Paso 4: 3 días con 3RO -> 3RO
        if (count3RO >= 3) return "3RO";
        // Paso 5: 4 días con 12HRS -> 3RO
        if (count12HRS >= 4) return "3RO";
        // Paso 6: 3 días 12HRS y 1 en 3RO -> 3RO
        if (count12HRS == 3 && count3RO >= 1) return "3RO";
        // Paso 7: 3 días 12HRS y el resto 2DO -> 2DO
        if (count12HRS == 3 && count2DO >= 1) return "2DO";

        // Paso 2: Determinar el más repetido
        int maxRepeticiones = Collections.max(conteo.values());
        List<String> empatados = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : conteo.entrySet()) {
            if (entry.getValue() == maxRepeticiones) {
                empatados.add(entry.getKey());
            }
        }

        if (empatados.size() == 1) {
            return empatados.get(0);
        }

        // Paso 3: En caso de empate, colocar el turno con el que se INICIA la semana
        for (String turnoDia : turnosCronologicos) {
            if (empatados.contains(turnoDia)) {
                return turnoDia;
            }
        }

        return "1RO"; // Fallback por defecto
    }
}