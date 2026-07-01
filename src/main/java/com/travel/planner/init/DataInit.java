package com.travel.planner.init;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInit {

    private final PlaceRepository placeRepository;

    @PostConstruct // 스프링 부트 서버가 켜질 때 딱 한 번 무조건 실행되는 어노테이션
    public void init() {
        // 이미 데이터가 있으면 중복 저장하지 않음
        if (placeRepository.count() > 0) {
            return;
        }

        // 💡 구글 맵스에서 직접 추출한 시즈오카 핵심 명소 5곳의 '진짜 좌표'
        Place p1 = new Place(); p1.setName("시즈오카역"); p1.setLatitude(34.9717); p1.setLongitude(138.3886);
        Place p2 = new Place(); p2.setName("슨푸성 공원"); p2.setLatitude(34.9788); p2.setLongitude(138.3828);
        Place p3 = new Place(); p3.setName("아오바 요코초 (오뎅거리)"); p3.setLatitude(34.9728); p3.setLongitude(138.3821);
        Place p4 = new Place(); p4.setName("니혼다이라 로프웨이"); p4.setLatitude(34.9733); p4.setLongitude(138.4651);
        Place p5 = new Place(); p5.setName("미호노마쓰바라 (후지산 뷰)"); p5.setLatitude(34.9938); p5.setLongitude(138.5233);

        // DB에 저장!
        placeRepository.save(p1);
        placeRepository.save(p2);
        placeRepository.save(p3);
        placeRepository.save(p4);
        placeRepository.save(p5);

        System.out.println("시즈오카 테스트 데이터 5건이 DB에 완벽하게 저장되었습니다!");
    }
}