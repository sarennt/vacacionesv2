package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.AsistenciaBiometrico;
import com.hrms.vacaciones.repository.AsistenciaBiometricoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsistenciaSemanalService {

    private final AsistenciaBiometricoRepository asistenciaRepository;
    private final CalculadoraAsistenciaService calculadoraService;

    @Transactional
    public void procesarSemanaContable(Integer nomina, LocalDate inicioSemana, LocalDate finSemana) {
        // 1. Extraemos toda la semana (Lunes a Domingo) del operador
        List<AsistenciaBiometrico> asistencias = asistenciaRepository.findByEmpleadoNominaAndFechaReferenciaBetweenOrderByFechaReferenciaAsc(nomina, inicioSemana, finSemana);

        if (asistencias.isEmpty()) return;

        // 2. Acumulamos los turnos para que el "Juez" decida
        List<String> turnosEjecutados = new ArrayList<>();
        double horasAcumuladasViernes = 0.0;
        int diasTrabajadosLunesAJueves = 0; // ✨ NUEVO CONTADOR
        boolean tieneFaltasSemana = false;
        AsistenciaBiometrico asistenciaViernes = null;

        for (AsistenciaBiometrico a : asistencias) {
            // Solo marcamos "falta" si el día en 0.0 es de Lunes a Viernes
            if (a.getFechaReferencia().getDayOfWeek().getValue() <= 5) {
                if (a.getHorasEfectivas() == null || a.getHorasEfectivas() == 0.0) {
                    tieneFaltasSemana = true;
                }
            }

            if (a.getHorasEfectivas() != null && a.getHorasEfectivas() > 0.0) {
                turnosEjecutados.add(a.getTurnoAsignado());

                // Acumulador exclusivo de Lunes a Jueves para la regla del 3RO
                if (a.getFechaReferencia().getDayOfWeek().getValue() >= 1 && a.getFechaReferencia().getDayOfWeek().getValue() <= 4) {
                    horasAcumuladasViernes += a.getHorasEfectivas();
                    diasTrabajadosLunesAJueves++; // ✨ CONTAMOS EL DÍA COMO TRABAJADO
                }
            }

            if (a.getFechaReferencia().getDayOfWeek() == DayOfWeek.FRIDAY) {
                asistenciaViernes = a;
            }
        }

        // 3. El Motor decide el turno dominante de la semana
        String turnoSemanal = calculadoraService.determinarTurnoDominante(turnosEjecutados);

        // 4. Aplicar Reglas Especiales por Día (Agrupando Dobles Turnos y Compensación 3RO)
        Map<LocalDate, Double> horasTotalesPorDia = new HashMap<>();
        for (AsistenciaBiometrico a : asistencias) {
            if (a.getHorasEfectivas() != null && a.getHorasEfectivas() > 0.0) {
                horasTotalesPorDia.put(a.getFechaReferencia(),
                        horasTotalesPorDia.getOrDefault(a.getFechaReferencia(), 0.0) + a.getHorasEfectivas());
            }
        }

        Map<LocalDate, Boolean> diaYaCalculado = new HashMap<>();

        for (AsistenciaBiometrico a : asistencias) {
            a.setTurnoDominante(turnoSemanal);

            if (a.getHorasEfectivas() != null && a.getHorasEfectivas() > 0.0) {
                double baseHoras = 8.0; // 1RO por defecto

                // Determinar base operativa según el turno dominante
                if ("2DO".equals(turnoSemanal) || "12HRS".equals(turnoSemanal)) {
                    baseHoras = 7.5;
                } else if ("3RO".equals(turnoSemanal)) {
                    // ✨ CORRECCIÓN: La base es 8.5 pareja para toda la semana.
                    // El bono del viernes (0.5) se inyecta por separado si cumple la semana perfecta.
                    baseHoras = 8.5;
                } else if ("MIXTO".equals(turnoSemanal)) {
                    baseHoras = (a.getFechaReferencia().getDayOfWeek() == DayOfWeek.MONDAY) ? 10.0 : 9.5;
                }

                // Sábado: Descanso para 3RO, 12HRS y MIXTO (Todo es tiempo extra)
                if (a.getFechaReferencia().getDayOfWeek() == DayOfWeek.SATURDAY) {
                    if ("3RO".equals(turnoSemanal) || "12HRS".equals(turnoSemanal) || "MIXTO".equals(turnoSemanal)) {
                        baseHoras = 0.0;
                    }
                }

                // Cálculo y Filtros de Tiempo Extra sobre el Total del Día
                if (a.getFechaReferencia().getDayOfWeek() == DayOfWeek.SUNDAY) {
                    if (!diaYaCalculado.getOrDefault(a.getFechaReferencia(), false)) {
                        a.setTiempoExtra(8.0); // Pago dominical
                        diaYaCalculado.put(a.getFechaReferencia(), true);
                    } else {
                        a.setTiempoExtra(0.0);
                    }
                } else {
                    if (!diaYaCalculado.getOrDefault(a.getFechaReferencia(), false)) {
                        double totalDia = horasTotalesPorDia.getOrDefault(a.getFechaReferencia(), 0.0);
                        double teCalculado = 0.0;

                        if (totalDia > baseHoras) {
                            teCalculado = Math.floor((totalDia - baseHoras) * 2) / 2.0;
                        }

                        // ✨ REGLA ESPECIAL 3RO: Compensar sumando +1 si el TE superó 1 hora
                        if ("3RO".equals(turnoSemanal) && teCalculado > 1.0) {
                            teCalculado += 1.0;
                        }

                        // REGLA DE ORO: Limpiar excedentes menores a 1 hora
                        if (teCalculado < 1.0) {
                            teCalculado = 0.0;
                        }

                        a.setTiempoExtra(teCalculado);
                        diaYaCalculado.put(a.getFechaReferencia(), true);
                    } else {
                        // El TE ya se asignó al primer bloque del doble turno
                        a.setTiempoExtra(0.0);
                    }
                }
            }
        }

        // ✨ REGLA DEL VIERNES (Exclusivo 3RO): Bono de media hora SOLO si cumplió el esquema planito
        if ("3RO".equals(turnoSemanal)) {
            // Buscamos un registro válido del viernes para no inyectar el bono en un huérfano de 0.0 hrs
            AsistenciaBiometrico registroViernes = asistencias.stream()
                    .filter(a -> a.getFechaReferencia().getDayOfWeek() == DayOfWeek.FRIDAY && a.getHorasEfectivas() != null && a.getHorasEfectivas() > 0.0)
                    .findFirst()
                    .orElse(null);

            if (registroViernes != null) {
                double horasViernes = horasTotalesPorDia.getOrDefault(registroViernes.getFechaReferencia(), 0.0);

                // Recalcular los días únicos físicos usando el mapa agrupado para evadir checadas múltiples
                double totalLunesAJueves = 0.0;
                int diasUnicosLunesAJueves = 0;

                for (Map.Entry<LocalDate, Double> entry : horasTotalesPorDia.entrySet()) {
                    int diaNum = entry.getKey().getDayOfWeek().getValue();
                    if (diaNum >= 1 && diaNum <= 4) {
                        totalLunesAJueves += entry.getValue();
                        if (entry.getValue() > 0.0) {
                            diasUnicosLunesAJueves++;
                        }
                    }
                }

                double totalLunesAViernes = totalLunesAJueves + horasViernes;

                // AD3=42.5 y validación de 4 días únicos físicos M-J (más el viernes = 5 días)
                // Eliminamos "tieneFaltasSemana" para que los huérfanos de 0.0 no saboteen el cálculo.
                if (Math.abs(totalLunesAViernes - 42.5) < 0.01 && Math.abs(horasViernes - 8.5) < 0.01 && diasUnicosLunesAJueves == 4) {
                    double extraActual = registroViernes.getTiempoExtra() != null ? registroViernes.getTiempoExtra() : 0.0;
                    registroViernes.setTiempoExtra(extraActual + 0.5);
                }
            }
        }

        // 5. Guardamos la semana completa con los ajustes finales
        asistenciaRepository.saveAll(asistencias);
        log.info("✅ Semana Contable procesada para nómina {}: Turno {}", nomina, turnoSemanal);
    }
}