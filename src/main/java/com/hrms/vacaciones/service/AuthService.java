package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.Empleado;
import com.hrms.vacaciones.repository.EmpleadoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final EmpleadoRepository empleadoRepository;

    @Autowired
    public AuthService(EmpleadoRepository empleadoRepository) {
        this.empleadoRepository = empleadoRepository;
    }

    public Empleado login(Integer nominaIngresada, String tagIngresado) {
        if (tagIngresado == null) {
            throw new RuntimeException("Nómina o TAG incorrectos");
        }
        
        String tagLimpio = tagIngresado.trim();
        
        Empleado empleado = empleadoRepository.findById(nominaIngresada)
                .orElseThrow(() -> new RuntimeException("Nómina o TAG incorrectos"));
                
        if (!tagLimpio.equals(empleado.getTag())) {
            throw new RuntimeException("Nómina o TAG incorrectos");
        }
        
        return empleado;
    }
}
