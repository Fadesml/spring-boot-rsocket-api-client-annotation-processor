package il.fadesml.rsocket.example;

import il.fadesml.rsocket.annotation.RSocketRequesterProvider;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.stereotype.Component;

@Component
public class ExampleRSocketRequesterProvider implements RSocketRequesterProvider {
    @Override
    public RSocketRequester getRSocketRequester() {
        return RSocketRequester.builder()
                .tcp("127.0.0.1", 1234);
    }
}
