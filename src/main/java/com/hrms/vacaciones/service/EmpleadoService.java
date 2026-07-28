package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.AuditoriaSaldo;
import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.repository.AuditoriaSaldoRepository;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import com.hrms.vacaciones.dto.AjusteSaldoRequest;
import com.hrms.vacaciones.dto.JefeDisponibleDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class EmpleadoService {

    @Autowired
    private EmpleadoRepository empleadoRepository;

    @Autowired
    private AuditoriaSaldoRepository auditoriaSaldoRepository;

    @Transactional
    public Empleado ajustarSaldoManual(AjusteSaldoRequest request) {

        if (request.getComentarioJustificacion() == null || request.getComentarioJustificacion().trim().isEmpty()) {
            throw new IllegalArgumentException("El comentario de justificación es estrictamente obligatorio para auditoría");
        }

        Empleado empleado = empleadoRepository.findById(request.getNominaEmpleado())
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado con nómina: " + request.getNominaEmpleado()));

        BigDecimal saldoActual = empleado.getSaldoVacacionesActual() != null ? empleado.getSaldoVacacionesActual() : BigDecimal.ZERO;
        BigDecimal saldoModificado = BigDecimal.valueOf(request.getDiasModificados());
        empleado.setSaldoVacacionesActual(saldoActual.add(saldoModificado));

        empleadoRepository.save(empleado);

        AuditoriaSaldo auditoria = new AuditoriaSaldo();
        auditoria.setNominaEmpleado(empleado.getNomina());
        auditoria.setDiasModificados(request.getDiasModificados());
        auditoria.setComentarioJustificacion(request.getComentarioJustificacion());
        auditoria.setFechaMovimiento(LocalDateTime.now());
        auditoria.setRealizadoPor(request.getRealizadoPor());

        auditoriaSaldoRepository.save(auditoria);

        return empleado;
    }

    // EL ORIGINAL (Corregido para que no truene la BD)
    public List<JefeDisponibleDTO> obtenerJefesDisponibles() {
        List<Empleado> todosLosJefes = empleadoRepository.findTodosLosJefes();

        return todosLosJefes.stream()
                .map(jefe -> new JefeDisponibleDTO(jefe.getNomina(), jefe.getNombreCompleto(), jefe.getPuesto()))
                .collect(Collectors.toList());
    }

    // NUEVO MÉTODO INTELIGENTE (Ya mapeado correctamente)
    public List<JefeDisponibleDTO> obtenerJefesPorAsignacion(Integer nominaEmpleado) {
        Empleado empleado = empleadoRepository.findById(nominaEmpleado)
                .orElseThrow(() -> new RuntimeException("Empleado no encontrado con nómina: " + nominaEmpleado));

        List<Empleado> jefesEncontrados = new java.util.ArrayList<>();

        // 🎯 REGLA DE ORO DE GRAMMER:
        if (empleado.getWorkCenter() != null) {
            // Es operador/sindicalizado de piso -> ligado a un WC con su shift asignado
            List<Empleado> shifts = empleadoRepository.findEmpleadosJefesPorWcId(empleado.getWorkCenter().getId());
            if (shifts != null) {
                jefesEncontrados.addAll(shifts);
            }
        } else {
            // Es administrativo -> tiene asignado un jefe directo por su nómina fija
            if (empleado.getJefeDirectoNomina() != null) {
                empleadoRepository.findById(empleado.getJefeDirectoNomina()).ifPresent(jefesEncontrados::add);
            }
            // Si el administrativo NO tiene jefe asignado (jefeDirectoNomina == null),
            // la lista se queda VACÍA intencionalmente para obligar el uso de la caja de evidencia.
        }

        return jefesEncontrados.stream()
                .map(jefe -> new JefeDisponibleDTO(jefe.getNomina(), jefe.getNombreCompleto(), jefe.getPuesto()))
                .collect(Collectors.toList());
    }
}
