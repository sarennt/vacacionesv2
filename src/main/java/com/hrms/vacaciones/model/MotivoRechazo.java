package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cat_motivos_rechazo", uniqueConstraints = {
        @UniqueConstraint(name = "uk_modulo_codigo", columnNames = {"modulo", "codigo"}) // ✨ Candado Compuesto
})
public class MotivoRechazo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 30)
    private String modulo; // 'VACACIONES' o 'PERMISOS'

    @Column(nullable = false, length = 50) // 🛡️ Sin unique individual
    private String codigo;

    @Column(nullable = false, length = 150)
    private String descripcion;

    @Column(nullable = false)
    private boolean activo;
}