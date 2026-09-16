package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.AsistenciaBiometrico;
import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.model.RangoTurno;
import com.hrms.vacaciones.model.RegistroBiometrico;
import com.hrms.vacaciones.repository.AsistenciaBiometricoRepository;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import com.hrms.vacaciones.repository.RangoTurnoRepository;
import com.hrms.vacaciones.repository.RegistroBiometricoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BiometricoImportService {

    private final EmpleadoRepository empleadoRepository;
    private final RegistroBiometricoRepository biometricoRepository;
    private final RangoTurnoRepository rangoTurnoRepository;
    private final AsistenciaBiometricoRepository asistenciaRepository;
    private final CalculadoraAsistenciaService calculadoraService;
    // ✨ INYECCIÓN DEL SERVICIO SEMANAL
    private final AsistenciaSemanalService asistenciaSemanalService;

    @Transactional
    public void importarRegistrosCSV(MultipartFile file) {
        DateTimeFormatter formatterFecha = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        List<RegistroBiometrico> registrosALotes = new ArrayList<>();
        Set<String> controlDuplicados = new HashSet<>();
        Set<Integer> nominasProcesadas = new HashSet<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
            String linea;

            while ((linea = br.readLine()) != null) {
                String[] columnas = linea.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                if (columnas.length < 24) continue;

                String nominaStr = columnas[20].replaceAll("[\"]", "").trim();
                String fechaSucia = columnas[22].replaceAll("[\"]", "").trim();
                String horaStr = columnas[23].replaceAll("[\"]", "").trim();

                if (nominaStr.isEmpty() || fechaSucia.isEmpty() || horaStr.isEmpty()) continue;

                Integer nomina = Integer.parseInt(nominaStr);
                String[] partesFecha = fechaSucia.split(" ");
                String fechaLimpiaStr = partesFecha.length > 1 ? partesFecha[1] : partesFecha[0];
                LocalDate fechaObj = LocalDate.parse(fechaLimpiaStr, formatterFecha);
                LocalTime horaOriginal = LocalTime.parse(horaStr);

                // ✅ DEJAR LA HORA INTACTA PARA NO PERDER LA SALIDA REAL
                LocalDateTime timestampFinal = LocalDateTime.of(fechaObj, horaOriginal);

                String llaveUnica = nomina + "-" + timestampFinal.toString();

                if (!controlDuplicados.contains(llaveUnica)) {
                    controlDuplicados.add(llaveUnica);

                    boolean yaExiste = biometricoRepository.existsByEmpleadoNominaAndTimestampRegistro(nomina, timestampFinal);

                    if (!yaExiste) {
                        Empleado emp = empleadoRepository.findById(nomina).orElse(null);
                        if (emp != null) {
                            RegistroBiometrico registro = RegistroBiometrico.builder()
                                    .empleado(emp)
                                    .fechaOriginal(fechaSucia)
                                    .fechaLimpia(fechaObj)
                                    .horaLimpia(horaOriginal) // Se guarda tal cual checó
                                    .horaOriginal(horaStr)
                                    .timestampRegistro(timestampFinal)
                                    .estatus("PROCESADO")
                                    .build();

                            registrosALotes.add(registro);
                            nominasProcesadas.add(nomina);
                        }
                    }
                }
            }

            biometricoRepository.saveAll(registrosALotes);
            log.info("✅ Importación Exitosa: {} registros limpios guardados.", registrosALotes.size());

            // ✨ EL NUEVO FLUJO UNIFICADO (Rollback -> Cazador -> Juez Semanal)
            if (!registrosALotes.isEmpty()) {
                // Delimitamos la semana contable afectada
                LocalDate inicioCsv = registrosALotes.get(0).getFechaLimpia().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                LocalDate finCsv = inicioCsv.plusDays(6);

                for (Integer nomina : nominasProcesadas) {
                    // 1. Desenredar registros viejos
                    rollbackYPrepararCazador(nomina, inicioCsv, finCsv);
                    // 2. Volver a unir entradas con salidas correctamente
                    cazarEntradasYSalidas(nomina);
                    // 3. Procesar turnos dominantes y horas extra
                    asistenciaSemanalService.procesarSemanaContable(nomina, inicioCsv, finCsv);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Error procesando el CSV bruto: " + e.getMessage(), e);
        }
    }

    private void cazarEntradasYSalidas(Integer nomina) {
        List<RegistroBiometrico> registros = biometricoRepository.findByEmpleadoNominaAndEstatusOrderByTimestampRegistroAsc(nomina, "PROCESADO");
        List<RangoTurno> matrizRangos = rangoTurnoRepository.findAll();
        List<AsistenciaBiometrico> asistencias = new ArrayList<>();

        for (int i = 0; i < registros.size(); i++) {
            RegistroBiometrico actual = registros.get(i);

            // ✨ Si es el último registro impar, se queda solo y le ponemos "CI"
            if (i == registros.size() - 1) {
                asistencias.add(crearAsistenciaHuerfana(actual, "CI"));
                actual.setEstatus("CI");
                break;
            }

            RegistroBiometrico siguiente = registros.get(i + 1);
            long horasDiferencia = java.time.Duration.between(actual.getTimestampRegistro(), siguiente.getTimestampRegistro()).toHours();

            // ✨ 1. Ampliamos la ventana de tolerancia hasta las 18 horas
            if (horasDiferencia <= 18) {
                // Aplicamos la macro híbrida especificando quién es quién
                LocalTime entradaManejada = aplicarRedondeoMacro(actual.getHoraLimpia(), true);
                LocalTime salidaManejada = aplicarRedondeoMacro(siguiente.getHoraLimpia(), false);

                String turnoAlias = cruzarConMatriz(entradaManejada, salidaManejada, matrizRangos);

                // Pasamos al motor las horas ya manejadas
                double horasEfectivas = calculadoraService.calcularHorasEfectivas(entradaManejada, salidaManejada);
                double horasJornada = obtenerHorasJornada(turnoAlias, matrizRangos, actual.getFechaLimpia());
                double tiempoExtra = calculadoraService.calcularTiempoExtra(entradaManejada, salidaManejada, horasJornada);

                AsistenciaBiometrico asistencia = AsistenciaBiometrico.builder()
                        .empleado(actual.getEmpleado())
                        .fechaReferencia(actual.getFechaLimpia())
                        .entrada(actual)
                        .salida(siguiente)
                        .turnoAsignado(turnoAlias)
                        .horasEfectivas(horasEfectivas)
                        .tiempoExtra(tiempoExtra)
                        .estatus(horasEfectivas == 0.0 ? "PSG" : (turnoAlias.equals("FALTA") ? "ATIPICO" : "COMPLETO"))
                        .build();

                asistencias.add(asistencia);

                actual.setEstatus("EMPAREJADO");
                siguiente.setEstatus("EMPAREJADO");
                i++;
            } else {
                // ✨ 2. Si pasa de 18 hrs, se rompe la regla de paridad y se marca Incompleta ("CI")
                asistencias.add(crearAsistenciaHuerfana(actual, "CI"));
                actual.setEstatus("CI");
            }
        }

        asistenciaRepository.saveAllAndFlush(asistencias);
        biometricoRepository.saveAllAndFlush(registros);
    }

    private double obtenerHorasJornada(String nombreTurno, List<RangoTurno> matriz, LocalDate fecha) {
        if (nombreTurno.equals("12HRS")) return 12.0;

        if (nombreTurno.equals("MIXTO")) {
            return fecha.getDayOfWeek().getValue() == 1 ? 10.0 : 9.5;
        }

        if (nombreTurno.equals("1RO") || nombreTurno.equals("2DO") || nombreTurno.equals("3RO")) return 8.0;

        return 8.0;
    }

    private String cruzarConMatriz(LocalTime entrada, LocalTime salida, List<RangoTurno> matriz) {
        for (RangoTurno rango : matriz) {
            boolean entradaOk = estaEnRango(entrada, rango.getEntradaDesde(), rango.getEntradaHasta());
            boolean salidaOk = estaEnRango(salida, rango.getSalidaDesde(), rango.getSalidaHasta());

            if (entradaOk && salidaOk) {
                return rango.getNombreTurno();
            }
        }
        return "FALTA";
    }

    private boolean estaEnRango(LocalTime hora, LocalTime desde, LocalTime hasta) {
        if (hasta.equals(LocalTime.MIDNIGHT)) {
            hasta = LocalTime.MAX;
        }

        if (!desde.isAfter(hasta)) {
            return !hora.isBefore(desde) && !hora.isAfter(hasta);
        }
        else {
            return !hora.isBefore(desde) || !hora.isAfter(hasta);
        }
    }

    // ✨ Actualizamos el método para recibir la etiqueta personalizada
    private AsistenciaBiometrico crearAsistenciaHuerfana(RegistroBiometrico huerfano, String estatusAtipico) {
        return AsistenciaBiometrico.builder()
                .empleado(huerfano.getEmpleado())
                .fechaReferencia(huerfano.getFechaLimpia())
                .entrada(huerfano)
                .salida(null)
                .turnoAsignado("ATIPICO")
                .estatus(estatusAtipico) // Aquí inyectamos el "CI"
                .horasEfectivas(0.0)
                .tiempoExtra(0.0)
                .build();
    }

    private LocalTime aplicarRedondeoMacro(LocalTime hora, boolean esEntrada) {
        int min = hora.getMinute();
        LocalTime horaAjustada = hora;

        if (esEntrada) {
            // Tolerancia de entrada (Ej. 06:31 -> 06:30 | 06:01 -> 06:00)
            if (min == 31) horaAjustada = hora.withMinute(30);
            else if (min == 1) horaAjustada = hora.withMinute(0);

            // PASO 2: Asesino de Tiempo Fantasma (Exclusivo para Entradas)
            if (!horaAjustada.isBefore(LocalTime.of(5, 30)) && !horaAjustada.isAfter(LocalTime.of(6, 30))) {
                return LocalTime.of(6, 30);
            } else if (!horaAjustada.isBefore(LocalTime.of(13, 0)) && !horaAjustada.isAfter(LocalTime.of(14, 30))) {
                return LocalTime.of(14, 30);
            } else if (!horaAjustada.isBefore(LocalTime.of(17, 30)) && !horaAjustada.isAfter(LocalTime.of(18, 30))) {
                return LocalTime.of(18, 30);
            } else if (!horaAjustada.isBefore(LocalTime.of(20, 30)) && !horaAjustada.isAfter(LocalTime.of(22, 0))) {
                return LocalTime.of(22, 0);
            }
            return horaAjustada;
        } else {
            // ✨ NUEVO: Cepillado estricto para Salidas (Redondeo a medias horas exactas)

            // Regalo de 1 minuto en salidas (Ej. 18:29 -> 18:30 | 15:59 -> 16:00)
            if (min == 29) return hora.withMinute(30);
            if (min == 59) return hora.plusHours(1).withMinute(0);

            // Castigo a la media hora anterior (Ej. 18:28 -> 18:00 | 15:34 -> 15:30)
            if (min >= 0 && min < 29) return hora.withMinute(0);
            if (min >= 30 && min < 59) return hora.withMinute(30);

            return hora;
        }
    }

    private void rollbackYPrepararCazador(Integer nomina, LocalDate inicio, LocalDate fin) {
        // 1. Destruimos los cálculos y emparejamientos viejos de la semana
        List<AsistenciaBiometrico> asistenciasViejas = asistenciaRepository
                .findByEmpleadoNominaAndFechaReferenciaBetweenOrderByFechaReferenciaAsc(nomina, inicio, fin);

        if (!asistenciasViejas.isEmpty()) {
            asistenciaRepository.deleteAll(asistenciasViejas);
        }

        // 2. Liberamos los registros faciales (EMPAREJADO -> PROCESADO) para que el cazador los vea de nuevo
        List<RegistroBiometrico> registrosVivos = biometricoRepository
                .findByEmpleadoNominaAndFechaLimpiaBetween(nomina, inicio, fin);

        for (RegistroBiometrico reg : registrosVivos) {
            reg.setEstatus("PROCESADO");
        }

        biometricoRepository.saveAll(registrosVivos);
    }
}