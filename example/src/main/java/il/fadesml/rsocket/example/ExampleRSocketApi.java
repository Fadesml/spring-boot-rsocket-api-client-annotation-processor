package il.fadesml.rsocket.example;

import il.fadesml.rsocket.annotation.RSocketApi;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RSocketApi(
        provider = ExampleRSocketRequesterProvider.class,
        generateAsComponent = true
)
public interface ExampleRSocketApi {

    @MessageMapping("example-request-response")
    Mono<ExampleDto> exampleRequestResponse(
            Mono<ExampleDto> data
    );

    @MessageMapping("example-request-response-with-destination-variable.{id}")
    Mono<ExampleDto> exampleRequestResponseWithDestinationVariable(
            @DestinationVariable String id,
            Mono<ExampleDto> data
    );

    @MessageMapping("example-fire-and-forget")
    Mono<Void> exampleFireAndForget(
            Mono<ExampleDto> data
    );

    @MessageMapping("example-request-stream")
    Flux<ExampleDto> exampleRequestStream(
            Mono<ExampleDto> data
    );

    @MessageMapping("example-request-channel")
    Flux<ExampleDto> exampleRequestChannel(
            Flux<ExampleDto> data
    );
}
