package com.travel.insurance.procedure;

import com.travel.insurance.common.exception.ResourceNotFoundException;
import com.travel.insurance.department.DepartmentService;
import com.travel.insurance.procedure.ProcedureNameNormalizer.CleanedName;
import com.travel.insurance.procedure.dto.ProcedureRequest;
import com.travel.insurance.procedure.dto.ProcedureResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ProcedureServiceImpl implements ProcedureService {

    private final ProcedureRepository procedureRepository;
    private final ProcedureMapper procedureMapper;
    private final ProcedureNameNormalizer nameNormalizer;
    private final ProcedureCodeGenerator codeGenerator;
    private final DepartmentService departmentService;

    @Override
    public ProcedureResponse create(ProcedureRequest request) {
        validateDepartment(request.departmentPublicId());
        CleanedName name = cleanRequiredName(request.name());
        rejectDuplicate(request.departmentPublicId(), name, null);

        Procedure procedure = procedureMapper.newProcedure(name, request.description(), request.departmentPublicId(), codeGenerator.next(), null);
        return procedureMapper.toResponse(save(procedure));
    }

    @Override
    @Transactional(readOnly = true)
    public ProcedureResponse getById(UUID id) {
        return procedureMapper.toResponse(getEntity(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProcedureResponse> list(String search, UUID departmentPublicId, Boolean active, Pageable pageable) {
        return procedureRepository.findAll(ProcedureSpecifications.filter(search, departmentPublicId, active), pageable)
                .map(procedureMapper::toResponse);
    }

    @Override
    public ProcedureResponse update(UUID id, ProcedureRequest request) {
        Procedure procedure = getEntity(id);
        validateDepartment(request.departmentPublicId());
        CleanedName name = cleanRequiredName(request.name());
        rejectDuplicate(request.departmentPublicId(), name, id);

        procedure.setName(name.display());
        procedure.setNormalizedName(name.normalized());
        procedure.setDescription(request.description());
        procedure.setDepartmentPublicId(request.departmentPublicId());
        return procedureMapper.toResponse(save(procedure));
    }

    @Override
    public ProcedureResponse activate(UUID id) {
        Procedure procedure = getEntity(id);
        if (!procedure.isActive()) {
            procedureRepository.findByDepartmentPublicIdAndNormalizedName(procedure.getDepartmentPublicId(), procedure.getNormalizedName())
                    .filter(other -> !other.getId().equals(procedure.getId()) && other.isActive())
                    .ifPresent(other -> {
                        throw new IllegalStateException("An active procedure named \"" + procedure.getName() + "\" already exists in this department");});
            procedure.setActive(true);
        }
        return procedureMapper.toResponse(save(procedure));
    }

    @Override
    public ProcedureResponse deactivate(UUID id) {
        Procedure procedure = getEntity(id);
        procedure.setActive(false);
        return procedureMapper.toResponse(save(procedure));
    }

    private void validateDepartment(UUID departmentPublicId) {
        departmentService.getEntityById(departmentPublicId);
    }

    private CleanedName cleanRequiredName(String rawName) {
        CleanedName name = nameNormalizer.clean(rawName);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Procedure name is required");
        }
        return name;
    }

    private void rejectDuplicate(UUID departmentPublicId, CleanedName name, UUID excludeId) {
        procedureRepository.findByDepartmentPublicIdAndNormalizedName(departmentPublicId, name.normalized())
                .filter(existing -> !existing.getId().equals(excludeId))
                .ifPresent(existing -> {
                    if (existing.isActive()) {
                        throw new IllegalStateException("A procedure named \"" + name.display() + "\" already exists in this department");
                    }
                    throw new IllegalStateException("An inactive procedure named \"" + name.display() + "\" already exists in this department. Reactivate it instead.");
                });
    }

    private Procedure save(Procedure procedure) {
        try {
            return procedureRepository.saveAndFlush(procedure);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("A procedure with the same name already exists in this department");
        }
    }

    private Procedure getEntity(UUID id) {
        return procedureRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Procedure", id));
    }
}
