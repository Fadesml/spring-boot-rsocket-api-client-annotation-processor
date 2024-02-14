package il.fadesml.rsocket.annotation;

import org.springframework.messaging.rsocket.RSocketRequester;

public interface RSocketRequesterProvider {
    RSocketRequester getRSocketRequester();

}
