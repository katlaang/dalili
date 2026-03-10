package dalili.com.base.domain.ai;

import java.util.ArrayList;
import java.util.List;

public record DifferentialInput(
        int age,
        String sex,
        String chiefComplaint,
        String vitals,
        List<String> symptoms,
        String history,
        String physicalExam
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int age;
        private String sex;
        private String chiefComplaint;
        private String vitals;
        private List<String> symptoms = new ArrayList<>();
        private String history;
        private String physicalExam;

        public Builder age(int val) {
            this.age = val;
            return this;
        }

        public Builder sex(String val) {
            this.sex = val;
            return this;
        }

        public Builder chiefComplaint(String val) {
            this.chiefComplaint = val;
            return this;
        }

        public Builder vitals(String val) {
            this.vitals = val;
            return this;
        }

        public Builder symptoms(List<String> val) {
            this.symptoms = new ArrayList<>(val);
            return this;
        }

        public Builder history(String val) {
            this.history = val;
            return this;
        }

        public Builder physicalExam(String val) {
            this.physicalExam = val;
            return this;
        }

        public DifferentialInput build() {
            return new DifferentialInput(age, sex, chiefComplaint, vitals, symptoms, history, physicalExam);
        }
    }
}
