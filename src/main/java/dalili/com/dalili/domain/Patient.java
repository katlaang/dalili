package dalili.com.dalili.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "patients")
public class Patient {

    @Getter
    @Id
    @GeneratedValue
    private UUID id;

    private String givenName;
    private String familyName;
    private LocalDate dateOfBirth;

    protected Patient() {
    }

    public Patient(String givenName, String familyName) {
        this.givenName = givenName;
        this.familyName = familyName;
    }

}
