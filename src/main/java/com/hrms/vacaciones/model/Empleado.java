package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "empleados")
public class Empleado {

    @Id
    private Integer nomina;

    private String tag;

    @Column(name = "first_last_name")
    private String apellidoPaterno;

    @Column(name = "second_last_name")
    private String apellidoMaterno;

    @Column(name = "name")
    private String nombres;

    private String contrato;
    private String puesto;

    @Column(name = "tipo_empleado")
    private String tipoEmpleado;

    @Column(name = "saldo_vacaciones_actual")
    private BigDecimal saldoVacacionesActual;

    @Column(name = "rol_jerarquico")
    private String rolJerarquico;

    @Column(name = "jefe_directo_nomina")
    private Integer jefeDirectoNomina;

    @Column(name = "correo_corporativo", length = 150)
    private String correoCorporativo;

    @Column(name = "es_perfil_confidencial")
    private Boolean esPerfilConfidencial;

    @Column(name = "estatus", length = 10)
    private String estatus = "ACTIVO"; // Puede ser 'ACTIVO' o 'BAJA'

    @Column(name = "antiguedad")
    private String antiguedadStr;

    // --- NUEVOS CAMPOS EXCLUSIVOS PARA CONTROL EXTRANJERO ---
    @Column(name = "dias_base_manual")
    private Integer diasBaseManual;

    @Column(name = "es_saldo_congelado")
    private Boolean esSaldoCongelado = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rol_id")
    private Rol rol;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "jefe_directo_id")
    private Empleado jefeDirecto;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "area_id")
    private Area area;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "centro_costo_id")
    private CentroCosto centroCosto;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "work_center_id")
    private WorkCenter workCenter;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "turno_id") // ✨ Nuestra llave foránea unificada
    private Turno turno;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "jefes_centros_costo",
            joinColumns = @JoinColumn(name = "nomina_jefe"),
            inverseJoinColumns = @JoinColumn(name = "centro_costo_id")
    )
    private List<CentroCosto> centrosCostoACargo;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "jefes_work_centers",
            joinColumns = @JoinColumn(name = "nomina_jefe"),
            inverseJoinColumns = @JoinColumn(name = "work_center_id")
    )
    private List<WorkCenter> workCentersACargo;

    public Empleado() {
    }

    public Integer getNomina() {
        return nomina;
    }

    public void setNomina(Integer nomina) {
        this.nomina = nomina;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getApellidoPaterno() {
        return apellidoPaterno;
    }

    public void setApellidoPaterno(String apellidoPaterno) {
        this.apellidoPaterno = apellidoPaterno;
    }

    public String getApellidoMaterno() {
        return apellidoMaterno;
    }

    public void setApellidoMaterno(String apellidoMaterno) {
        this.apellidoMaterno = apellidoMaterno;
    }

    public String getNombres() {
        return nombres;
    }

    public void setNombres(String nombres) {
        this.nombres = nombres;
    }

    public String getContrato() {
        return contrato;
    }

    public void setContrato(String contrato) {
        this.contrato = contrato;
    }

    public String getPuesto() {
        return puesto;
    }

    public void setPuesto(String puesto) {
        this.puesto = puesto;
    }

    public String getTipoEmpleado() {
        return tipoEmpleado;
    }

    public void setTipoEmpleado(String tipoEmpleado) {
        this.tipoEmpleado = tipoEmpleado;
    }

    public BigDecimal getSaldoVacacionesActual() {
        return saldoVacacionesActual;
    }

    public void setSaldoVacacionesActual(BigDecimal saldoVacacionesActual) {
        this.saldoVacacionesActual = saldoVacacionesActual;
    }

    public String getRolJerarquico() {
        return rolJerarquico;
    }

    public void setRolJerarquico(String rolJerarquico) {
        this.rolJerarquico = rolJerarquico;
    }

    public Integer getJefeDirectoNomina() {
        return jefeDirectoNomina;
    }

    public void setJefeDirectoNomina(Integer jefeDirectoNomina) {
        this.jefeDirectoNomina = jefeDirectoNomina;
    }

    public String getCorreoCorporativo() {
        return correoCorporativo;
    }

    public void setCorreoCorporativo(String correoCorporativo) {
        this.correoCorporativo = correoCorporativo;
    }

    public Boolean getEsPerfilConfidencial() {
        return esPerfilConfidencial;
    }

    public void setEsPerfilConfidencial(Boolean esPerfilConfidencial) {
        this.esPerfilConfidencial = esPerfilConfidencial;
    }

    public String getEstatus() {
        return estatus;
    }

    public void setEstatus(String estatus) {
        this.estatus = estatus;
    }

    public Integer getDiasBaseManual() {
        return diasBaseManual;
    }

    public void setDiasBaseManual(Integer diasBaseManual) {
        this.diasBaseManual = diasBaseManual;
    }

    public Boolean getEsSaldoCongelado() {
        return esSaldoCongelado;
    }

    public void setEsSaldoCongelado(Boolean esSaldoCongelado) {
        this.esSaldoCongelado = esSaldoCongelado;
    }

    public Rol getRol() {
        return rol;
    }

    public void setRol(Rol rol) {
        this.rol = rol;
    }

    public Empleado getJefeDirecto() {
        return jefeDirecto;
    }

    public void setJefeDirecto(Empleado jefeDirecto) {
        this.jefeDirecto = jefeDirecto;
    }

    public Area getArea() {
        return area;
    }

    public void setArea(Area area) {
        this.area = area;
    }

    public CentroCosto getCentroCosto() {
        return centroCosto;
    }

    public void setCentroCosto(CentroCosto centroCosto) {
        this.centroCosto = centroCosto;
    }

    public WorkCenter getWorkCenter() {
        return workCenter;
    }

    public void setWorkCenter(WorkCenter workCenter) {
        this.workCenter = workCenter;
    }

    public Turno getTurno() {
        return turno;
    }

    public void setTurno(Turno turno) {
        this.turno = turno;
    }

    public List<CentroCosto> getCentrosCostoACargo() {
        return centrosCostoACargo;
    }

    public void setCentrosCostoACargo(List<CentroCosto> centrosCostoACargo) {
        this.centrosCostoACargo = centrosCostoACargo;
    }

    public List<WorkCenter> getWorkCentersACargo() {
        return workCentersACargo;
    }

    public void setWorkCentersACargo(List<WorkCenter> workCentersACargo) {
        this.workCentersACargo = workCentersACargo;
    }

    public String getNombreCompleto() {
        String base = "";
        if (nombres != null) base += nombres;
        if (apellidoPaterno != null) base += " " + apellidoPaterno;
        if (apellidoMaterno != null) base += " " + apellidoMaterno;
        return base.trim().isEmpty() ? "Nombre Completo" : base.trim();
    }

    public java.time.LocalDate getFechaIngreso() {
        if (this.antiguedadStr == null || this.antiguedadStr.trim().isEmpty()) {
            return null;
        }
        try {
            String fechaLimpia = this.antiguedadStr.split(" ")[0].replace("/", "-");
            return java.time.LocalDate.parse(fechaLimpia);
        } catch (Exception e) {
            return null;
        }
    }

    public void setFechaIngreso(java.time.LocalDate fecha) {
        this.antiguedadStr = (fecha != null) ? fecha.toString() : null;
    }



    public int calcularDiasDerecho(int año) {
        int diasLey;
        if (año == 1) diasLey = 12;
        else if (año == 2) diasLey = 14;
        else if (año == 3) diasLey = 16;
        else if (año == 4) diasLey = 18;
        else if (año == 5) diasLey = 20;
        else if (año <= 10) diasLey = 22;
        else if (año <= 15) diasLey = 24;
        else if (año <= 20) diasLey = 26;
        else diasLey = 28;

        if ("EXTRANJERO".equalsIgnoreCase(this.tipoEmpleado)) {
            if (Boolean.TRUE.equals(this.esSaldoCongelado)) {
                return (this.diasBaseManual != null) ? this.diasBaseManual : 18;
            }
            if (this.diasBaseManual != null) {
                int bonoFijo = this.diasBaseManual - 12;
                return diasLey + bonoFijo;
            }
            return diasLey;
        }

        if ("ADMINISTRATIVO".equalsIgnoreCase(this.tipoEmpleado)) {
            return diasLey + 2;
        }
        return diasLey;
    }


    @Transient
    public java.math.BigDecimal getSaldoCiclosAnteriores() {
        java.time.LocalDate fechaIngreso = getFechaIngreso();
        if (fechaIngreso == null) return java.math.BigDecimal.ZERO;

        java.time.LocalDate hoy = java.time.LocalDate.now();
        int anosCumplidos = java.time.Period.between(fechaIngreso, hoy).getYears();

        int diasNuevosPeriodo = calcularDiasDerecho(anosCumplidos);
        java.math.BigDecimal actual = (this.saldoVacacionesActual != null) ? this.saldoVacacionesActual : java.math.BigDecimal.ZERO;

        java.math.BigDecimal saldoAtrasado = actual.subtract(java.math.BigDecimal.valueOf(diasNuevosPeriodo));
        return saldoAtrasado.compareTo(java.math.BigDecimal.ZERO) > 0 ? saldoAtrasado : java.math.BigDecimal.ZERO;
    }

    @Transient
    public java.math.BigDecimal getSaldoPeriodoActual() {
        java.math.BigDecimal actual = (this.saldoVacacionesActual != null) ? this.saldoVacacionesActual : java.math.BigDecimal.ZERO;
        return actual.subtract(getSaldoCiclosAnteriores());
    }

    @Transient
    public java.time.LocalDate getFechaLimiteExpiracion() {
        java.time.LocalDate fechaIngreso = getFechaIngreso();
        if (fechaIngreso == null) return null;

        java.time.LocalDate hoy = java.time.LocalDate.now();
        int anosCumplidos = java.time.Period.between(fechaIngreso, hoy).getYears();

        java.time.LocalDate ultimoAniversario = fechaIngreso.plusYears(anosCumplidos);
        return ultimoAniversario.plusMonths(6);
    }

    // 1. Cálculo matemático puro en bruto (Sin brincos automáticos ni ceguera)
    @Transient
    public BigDecimal getSaldoProporcionalRaw() {
        java.time.LocalDate fecha = getFechaIngreso();
        if (fecha == null) return BigDecimal.ZERO;

        java.time.LocalDate hoy = java.time.LocalDate.now();
        int añosCumplidos = java.time.Period.between(fecha, hoy).getYears();

        java.time.LocalDate ultimoAniversario = fecha.plusYears(añosCumplidos);
        long diasTranscurridos = java.time.temporal.ChronoUnit.DAYS.between(ultimoAniversario, hoy);

        // 🚨 ELIMINAMOS EL CAMBIO A 365 DÍAS PARA DEJAR EL CÁLCULO REAL DÍA POR DÍA
        int añoEnCurso = añosCumplidos + 1;
        int diasDerechoAnual = calcularDiasDerecho(añoEnCurso);

        // Multiplicamos primero y luego dividimos para no perder precisión decimal en el camino
        return BigDecimal.valueOf(diasDerechoAnual)
                .multiply(BigDecimal.valueOf(diasTranscurridos))
                .divide(BigDecimal.valueOf(365), 2, java.math.RoundingMode.HALF_UP);
    }

    // 2. El método oficial que lee tu Dashboard (Neteado para evitar engaños visuales)
    @Transient
    public BigDecimal getSaldoProporcional() {
        BigDecimal proporcionalEnBruto = getSaldoProporcionalRaw();

        // 🛡️ ¡EL CANDADO MAESTRO! Si el saldo devengado actual es negativo (días adelantados),
        // se los restamos directamente al proporcional visible en el Dashboard.
        if (this.saldoVacacionesActual != null && this.saldoVacacionesActual.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal saldoNeteado = proporcionalEnBruto.add(this.saldoVacacionesActual); // Suma porque actual ya es negativo (ej: 21.44 + (-7) = 14.44)
            return saldoNeteado.compareTo(BigDecimal.ZERO) > 0 ? saldoNeteado : BigDecimal.ZERO;
        }

        return proporcionalEnBruto;
    }

    // 3. El Saldo Acumulado Total Neto (Suma real de ambos mundos)
    @Transient
    public BigDecimal getSaldoTotalDisponible() {
        BigDecimal actual = (saldoVacacionesActual != null) ? saldoVacacionesActual : BigDecimal.ZERO;
        // Sumamos el real de base de datos más el bruto proporcional para evitar un doble descuento
        return actual.add(getSaldoProporcionalRaw());
    }
}