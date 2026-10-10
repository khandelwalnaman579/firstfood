package com.firstfood.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AbsenceLookupServiceImpl implements AbsenceLookupService {

    private final AbsenceRecordRepository absences;

    AbsenceLookupServiceImpl(AbsenceRecordRepository absences) {
        this.absences = absences;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalDate> declaredAbsenceDates(UUID subscriptionId) {
        return absences.findDeclaredDates(subscriptionId);
    }
}
