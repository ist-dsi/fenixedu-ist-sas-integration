package org.fenixedu.ulisboa.integration.sas.service.process;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import org.fenixedu.academic.domain.ExecutionInterval;
import org.fenixedu.academic.domain.ExecutionYear;
import org.fenixedu.academic.domain.StudentCurricularPlan;
import org.fenixedu.academic.domain.degree.DegreeType;
import org.fenixedu.academic.domain.student.Registration;
import org.fenixedu.academic.domain.student.Student;
import org.fenixedu.academic.domain.student.curriculum.Curriculum;
import org.fenixedu.academic.domain.studentCurriculum.Credits;
import org.fenixedu.ulisboa.integration.sas.domain.ScholarshipReportRequest;
import org.fenixedu.ulisboa.integration.sas.domain.SocialServicesConfiguration;
import org.fenixedu.ulisboa.integration.sas.dto.AbstractScholarshipStudentBean;
import org.fenixedu.ulisboa.integration.sas.dto.ScholarshipStudentOtherYearBean;
import org.fenixedu.ulisboa.integration.sas.service.registration.report.RegistrationHistoryReport;
import org.fenixedu.ulisboa.integration.sas.service.registration.report.RegistrationHistoryReportService;
import org.fenixedu.ulisboa.integration.sas.util.SASDomainException;
import org.joda.time.DateTime;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

public class FillScholarshipServiceOtherYearService extends AbstractFillScholarshipService {

    @Override
    protected void fillSpecificInfo(AbstractScholarshipStudentBean inputBean,
            RegistrationHistoryReport currentYearRegistrationReport, ScholarshipReportRequest request) {

        final Registration registration = currentYearRegistrationReport.getRegistration();
        final ScholarshipStudentOtherYearBean bean = (ScholarshipStudentOtherYearBean) inputBean;

        bean.setNumberOfDegreeChanges(calculateNumberOfDegreeChanges(registration.getStudent(), request));
        bean.setHasMadeDegreeChangeOnCurrentYear(calculateDegreeChangeForCurrentYear(registration, request));

        bean.setCurricularYear(currentYearRegistrationReport.getCurricularYear());

        bean.setCycleIngressionYear(calculateCycleIngressionYear(bean, registration));

        final Collection<RegistrationHistoryReport> cycleRegistrationReports =
                calculateRegistrationReports(registration.getStudent(), registration.getDegreeType(), request.getExecutionYear());

        bean.setCycleNumberOfEnrolmentYears(calculateCycleNumberOfEnrolmentYears(cycleRegistrationReports, request));
        bean.setCycleNumberOfEnrolmentsYearsInIntegralRegime(
                calculateCycleNumberOfEnrolmentYearsInIntegralRegime(cycleRegistrationReports, request));

        final RegistrationHistoryReport lastEnrolmentYearHistory = calculateCycleLastEnrolmentYearHistory(registration, request);
        checkIfHasDismissals(bean, registration, lastEnrolmentYearHistory);

        if (lastEnrolmentYearHistory != null) {
            bean.setLastEnrolmentYear(lastEnrolmentYearHistory.getExecutionInterval().getBeginDateYearMonthDay().getYear());
            bean.setLastEnrolmentCurricularYear(lastEnrolmentYearHistory.getCurricularYear());

            final ExecutionYear executionYear = ExecutionInterval.assertExecutionIntervalType(ExecutionYear.class,
                    lastEnrolmentYearHistory.getExecutionInterval());

            if (!isInMobility(registration, executionYear)) {
                bean.setNumberOfEnrolledEctsLastYear(lastEnrolmentYearHistory.getTotalEnroledCredits());
                bean.setNumberOfApprovedEctsLastYear(lastEnrolmentYearHistory.getTotalApprovedCredits());
            } else {
                // TODO add the approved and enrolled credits obtained in erasmus (information to be extracted from mobility - roadmap)
                bean.setNumberOfEnrolledEctsLastYear(lastEnrolmentYearHistory.getTotalEnroledCredits());
                bean.setNumberOfApprovedEctsLastYear(lastEnrolmentYearHistory.getTotalApprovedCredits());
            }

            bean.setLastAcademicActDateLastYear(lastEnrolmentYearHistory.getLastAcademicActDate());
        }

        bean.setNumberOfApprovedEcts(calculateNumberOfApprovedEcts(registration, request));
    }

    private BigDecimal calculateNumberOfApprovedEcts(Registration registration, ScholarshipReportRequest request) {
        ExecutionYear executionYear = request.getExecutionYear();

        final StudentCurricularPlan studentCurricularPlan;
        final Curriculum curriculum;
        if (executionYear.hasNextExecutionYear()) {
            // the executionYear has next year, so it gets the curriculum plan of the beginning of next year)
            studentCurricularPlan = registration.getStudentCurricularPlan(executionYear.getNextExecutionYear());

            if (studentCurricularPlan == null) {
                throw new SASDomainException(
                        "error.RegistrationHistoryReportService.unable.to.find.student.curricular.plan.for.year",
                        registration.getStudent().getNumber().toString(), executionYear.getQualifiedName());
            }

            curriculum = studentCurricularPlan.getCurriculum(new DateTime(), executionYear.getNextExecutionYear());
        } else {
            // current execution year
            // get the curriculum with date = null
            studentCurricularPlan = registration.getStudentCurricularPlan(executionYear);

            if (studentCurricularPlan == null) {
                throw new SASDomainException(
                        "error.RegistrationHistoryReportService.unable.to.find.student.curricular.plan.for.year",
                        registration.getStudent().getNumber().toString(), executionYear.getQualifiedName());
            }

            curriculum = studentCurricularPlan.getCurriculum(new DateTime(), null);

        }
        return curriculum.getSumEctsCredits();
    }

    private void checkIfHasDismissals(final ScholarshipStudentOtherYearBean bean, final Registration registration,
            final RegistrationHistoryReport lastEnrolmentYearHistory) {

        // report any in the last enrolment year
        if (lastEnrolmentYearHistory != null) {
            final ExecutionYear year = ExecutionInterval.assertExecutionIntervalType(ExecutionYear.class,
                    lastEnrolmentYearHistory.getExecutionInterval());

            BigDecimal givenCredits = BigDecimal.ZERO;
            for (final StudentCurricularPlan iter : registration.getStudentCurricularPlansSet()) {
                for (final Credits credits : iter.getCreditsSet()) {
                    if (credits.getExecutionPeriod().getExecutionYear() == year) {
                        givenCredits = givenCredits.add(BigDecimal.valueOf(credits.getGivenCredits()));
                    }
                }
            }

            if (!BigDecimal.ZERO.equals(givenCredits)) {
                addWarning(bean, "A matrícula tem " + givenCredits.toPlainString() + " ECTS em creditações no ano lectivo "
                        + year.getQualifiedName() + ".");
            }
        }

        // report an year only with dismissals
        final Set<ExecutionYear> curriculumLineYears = getExecutionYears(registration,
                r -> r.getApprovedCurriculumLines().stream().map(e -> e.getExecutionYear()), ey -> true);
        final Set<ExecutionYear> enrolmentYears = getExecutionYears(registration,
                r -> r.getApprovedEnrolments().stream().map(e -> e.getExecutionYear()), ey -> true);
        for (final ExecutionYear iter : Sets.difference(curriculumLineYears, enrolmentYears)) {
            addWarning(bean, "A matrícula tem APENAS creditações no ano lectivo " + iter.getQualifiedName() + ".");
        }
    }

    private Integer calculateCycleNumberOfEnrolmentYearsInIntegralRegime(
            Collection<RegistrationHistoryReport> cycleRegistrationReports, ScholarshipReportRequest request) {

        Set<ExecutionYear> executionYears = Sets.newHashSet();
        for (RegistrationHistoryReport registrationHistoryReport : cycleRegistrationReports) {

            Registration registration = registrationHistoryReport.getRegistration();
            Collection<ExecutionYear> enrolmentYearsIncludingPrecedentRegistrations =
                    getEnrolmentYearsIncludingPrecedentDegrees(registration, request.getExecutionYear());

            for (ExecutionYear executionYear : enrolmentYearsIncludingPrecedentRegistrations) {
                if (!registrationHistoryReport.getRegistration().isPartialRegime(executionYear))
                    executionYears.add(executionYear);
            }
        }
        return executionYears.size();
    }

    static private Set<ExecutionYear> getEnrolmentYearsIncludingPrecedentDegrees(final Registration registration,
            final ExecutionYear year) {

        return getExecutionYears(registration, r -> r.getEnrolmentsExecutionYears().stream(), ey -> ey.isBeforeOrEquals(year));
    }

    private Integer calculateCycleNumberOfEnrolmentYears(Collection<RegistrationHistoryReport> cycleRegistrationReports,
            ScholarshipReportRequest request) {

        Set<ExecutionYear> executionYears = Sets.newHashSet();
        for (RegistrationHistoryReport registrationHistoryReport : cycleRegistrationReports) {
            executionYears.addAll(getEnrolmentYearsIncludingPrecedentDegrees(registrationHistoryReport.getRegistration(),
                    request.getExecutionYear()));
        }
        return executionYears.size();
    }

    private Collection<RegistrationHistoryReport> calculateRegistrationReports(Student student, DegreeType degreeType,
            ExecutionYear executionYear) {
        final Set<RegistrationHistoryReport> result = Sets.newHashSet();
        final RegistrationHistoryReportService service = new RegistrationHistoryReportService();
        for (final Registration registration : student.getRegistrationsByDegreeTypes(degreeType)) {
            result.add(service.generateReport(registration, executionYear));
        }

        return result;
    }

    private Integer calculateCycleIngressionYear(ScholarshipStudentOtherYearBean bean, Registration registration) {

        final Registration firstRegistration =
                Collections.min(registration.getStudent().getRegistrationsByDegreeTypes(registration.getDegreeType()),
                        Registration.COMPARATOR_BY_START_DATE);

        Integer firstRegistrationYear =
                firstRegistration.getStartExecutionYear().getBeginDateYearMonthDay().getYear();

        if (bean.getCycleIngressionYear() != null && !bean.getCycleIngressionYear().equals(firstRegistrationYear)) {
            String message = "o ano de ingresso no ciclo de estudos declarado no ficheiro (" + bean.getCycleIngressionYear()
                    + ") não corresponde ao ano de início do sistema (" + firstRegistrationYear + ").";
            addWarning(bean, message);
        }

        return firstRegistrationYear;
    }

    private RegistrationHistoryReport calculateCycleLastEnrolmentYearHistory(Registration currentYearRegistration,
            ScholarshipReportRequest request) {

        final SortedSet<ExecutionYear> allEnrolmentYears = Sets.newTreeSet(ExecutionYear.COMPARATOR_BY_BEGIN_DATE);

        final Multimap<Registration, ExecutionYear> enrolmentYearsByRegistration = ArrayListMultimap.create();

        for (final Registration registration : currentYearRegistration.getStudent()
                .getRegistrationsByDegreeTypes(currentYearRegistration.getDegreeType())) {

            final Collection<ExecutionYear> enrolmentYears =
                    getEnrolmentYearsIncludingPrecedentDegrees(registration, request.getExecutionYear());
            enrolmentYearsByRegistration.putAll(registration, enrolmentYears);
            allEnrolmentYears.addAll(enrolmentYears);
        }

        allEnrolmentYears.remove(request.getExecutionYear());

        if (allEnrolmentYears.isEmpty()) {
            return null;
        }

        final ExecutionYear lastEnrolmentYear = allEnrolmentYears.last();
        for (final Map.Entry<Registration, Collection<ExecutionYear>> entry : enrolmentYearsByRegistration.asMap().entrySet()) {
            if (entry.getValue().contains(lastEnrolmentYear)) {
                final Registration registration = entry.getKey();
                final RegistrationHistoryReportService service = new RegistrationHistoryReportService();

                return service.generateReport(registration, lastEnrolmentYear);
            }
        }

        return null;
    }

    private Boolean calculateDegreeChangeForCurrentYear(Registration registration, ScholarshipReportRequest request) {

        return registration.getStudent().getRegistrationsSet().stream()
                .filter(r -> r.getStartExecutionYear() == request.getExecutionYear()).anyMatch(r -> SocialServicesConfiguration
                        .getInstance().getIngressionTypeWhichAreDegreeTransferSet().contains(r.getIngressionType()));
    }

    private Integer calculateNumberOfDegreeChanges(Student student, ScholarshipReportRequest request) {

        Integer degreeChangeCount = 0;
        for (final Registration iter : student.getRegistrationsSet()) {
            if (SocialServicesConfiguration.getInstance().getIngressionTypeWhichAreDegreeTransferSet()
                    .contains(iter.getIngressionType())) {
                degreeChangeCount++;
            }
        }
        return degreeChangeCount;
    }
}