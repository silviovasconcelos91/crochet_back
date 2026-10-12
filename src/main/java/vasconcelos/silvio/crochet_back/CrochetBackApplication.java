package vasconcelos.silvio.crochet_back;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import vasconcelos.silvio.crochet_back.config.NativeHints;

@SpringBootApplication
@ImportRuntimeHints(NativeHints.class)
public class CrochetBackApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrochetBackApplication.class, args);
    }

}
