FROM adzerk/boot-clj

ARG DATOMIC_USER
ARG DATOMIC_PASSWORD
ARG CCARTUSER
ARG CCARTPASS

VOLUME /usr/src/app
WORKDIR /usr/src/app

COPY build.boot /usr/src/app/

RUN ["boot", "show", "-d"]

EXPOSE 35001 8000 5005

CMD ["repl", "--server", "--port", "35001", "--host", "0.0.0.0", "--", "wait"]